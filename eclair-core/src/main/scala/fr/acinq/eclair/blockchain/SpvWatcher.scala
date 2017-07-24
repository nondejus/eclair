package fr.acinq.eclair.blockchain

import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.{FutureCallback, Futures}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{Globals, NodeParams}
import org.bitcoinj.core.{Transaction => BitcoinjTransaction}
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.script.Script

import scala.collection.SortedMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

final case class Hint(script: Script)

final case class NewConfidenceLevel(tx: Transaction, blockHeight: Int, confirmations: Int) extends BlockchainEvent

/**
  * A blockchain watcher that:
  * - receives bitcoin events (new blocks and new txes) directly from the bitcoin network
  * - also uses bitcoin-core rpc api, most notably for tx confirmation count and blockcount (because reorgs)
  * Created by PM on 21/02/2016.
  */
class SpvWatcher(nodeParams: NodeParams, kit: WalletAppKit)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[BlockchainEvent])
  context.system.eventStream.subscribe(self, classOf[NewConfidenceLevel])

  context.system.scheduler.schedule(10 seconds, 1 minute, self, 'tick)

  val broadcaster = context.actorOf(Props(new Broadcaster(kit: WalletAppKit)), name = "broadcaster")

  case class TriggerEvent(w: Watch, e: WatchEvent)

  def receive: Receive = watching(Set(), SortedMap(), Nil)

  def watching(watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]], oldEvents: Seq[NewConfidenceLevel]): Receive = {

    case event@NewConfidenceLevel(tx, blockHeight, confirmations) =>
      log.debug(s"analyzing txid=${tx.txid} confirmations=$confirmations tx=${Transaction.write(tx)}")
      watches.collect {
        case w@WatchSpentBasic(_, txid, outputIndex, event) if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          self ! TriggerEvent(w, WatchEventSpentBasic(event))
        case w@WatchSpent(_, txid, outputIndex, event) if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          self ! TriggerEvent(w, WatchEventSpent(event, tx))
        case w@WatchConfirmed(_, txId, minDepth, event) if txId == tx.txid && confirmations >= minDepth =>
          self ! TriggerEvent(w, WatchEventConfirmed(event, blockHeight, 0))
        case w@WatchConfirmed(_, txId, minDepth, event) if txId == tx.txid && confirmations == -1 =>
          // the transaction watched was overriden by a competing tx
          self ! TriggerEvent(w, WatchEventDoubleSpent(event))
      }
      context become watching(watches, block2tx, oldEvents.filterNot(_.tx.txid == tx.txid) :+ event)

    case TriggerEvent(w, e) if watches.contains(w) =>
      log.info(s"triggering $w")
      w.channel ! e
      // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
      // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
      if (!w.isInstanceOf[WatchSpent]) context.become(watching(watches - w, block2tx, oldEvents))

    case CurrentBlockCount(count) => {
      val toPublish = block2tx.filterKeys(_ <= count)
      toPublish.values.flatten.map(tx => publish(tx))
      context.become(watching(watches, block2tx -- toPublish.keys, oldEvents))
    }

    case hint: Hint => kit.wallet().addWatchedScripts(ImmutableList.of(hint.script))

    case w: Watch if !watches.contains(w) =>
      log.debug(s"adding watch $w for $sender")
      log.warning(s"resending ${oldEvents.size} events in order!")
      oldEvents.foreach(self ! _)
      context.watch(w.channel)
      context.become(watching(watches + w, block2tx, oldEvents))

    case PublishAsap(tx) =>
      val blockCount = Globals.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=${Transaction.write(tx)}")
        self ! WatchConfirmed(self, parentTxid, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, tx +: block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]))
        context.become(watching(watches, block2tx1, oldEvents))
      } else publish(tx)

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = Globals.blockCount.get()
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = blockHeight + csvTimeout
      if (absTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(absTimeout, tx +: block2tx.getOrElse(absTimeout, Seq.empty[Transaction]))
        context.become(watching(watches, block2tx1, oldEvents))
      } else publish(tx)

    case ParallelGetRequest(announcements) => sender ! ParallelGetResponse(announcements.map {
      case c =>
        log.info(s"blindly validating channel=$c")
        val pubkeyScript = write(pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
        val fakeFundingTx = Transaction(
          version = 2,
          txIn = Seq.empty[TxIn],
          txOut = TxOut(Satoshi(0), pubkeyScript) :: Nil,
          lockTime = 0)
        IndividualResult(c, Some(fakeFundingTx), true)
    })

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.channel == channel)
      context.become(watching(watches -- deprecatedWatches, block2tx, oldEvents))

    case 'watches => sender ! watches

  }

  def publish(tx: Transaction): Unit = broadcaster ! tx

}

object SpvWatcher {

  def props(nodeParams: NodeParams, kit: WalletAppKit)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(new SpvWatcher(nodeParams, kit)(ec))

}

class Broadcaster(kit: WalletAppKit) extends Actor with ActorLogging {

  override def receive: Receive = {
    case tx: Transaction =>
      broadcast(tx)
      context become waiting(Nil)
  }

  def waiting(stash: Seq[Transaction]): Receive = {
    case BroadcastResult(tx, result) =>
      result match {
        case Success(_) => log.info(s"broadcast success for txid=${tx.txid}")
        case Failure(t) => log.error(t, s"broadcast failure for txid=${tx.txid}: ")
      }
      stash match {
        case head :: rest =>
          broadcast(head)
          context become waiting(rest)
        case Nil => context become receive
      }
    case tx: Transaction =>
      log.info(s"stashing txid=${tx.txid} for broadcast")
      context become waiting(stash :+ tx)
  }

  case class BroadcastResult(tx: Transaction, result: Try[Boolean])
  def broadcast(tx: Transaction) = {
    val bitcoinjTx = new org.bitcoinj.core.Transaction(kit.wallet().getParams, Transaction.write(tx))
    log.info(s"broadcasting txid=${tx.txid}")
    Futures.addCallback(kit.peerGroup().broadcastTransaction(bitcoinjTx).future(), new FutureCallback[BitcoinjTransaction] {
      override def onFailure(t: Throwable): Unit = self ! BroadcastResult(tx, Failure(t))

      override def onSuccess(v: BitcoinjTransaction): Unit = self ! BroadcastResult(tx, Success(true))
    }, context.dispatcher)
  }



}
