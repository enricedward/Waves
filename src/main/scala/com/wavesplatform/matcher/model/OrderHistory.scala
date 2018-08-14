package com.wavesplatform.matcher.model

import cats.implicits._
import cats.kernel.Monoid
import com.wavesplatform.database.{DBExt, RW}
import com.wavesplatform.matcher.api.DBUtils
import com.wavesplatform.matcher.model.Events._
import com.wavesplatform.matcher.model.LimitOrder.{Filled, OrderStatus}
import com.wavesplatform.matcher.{MatcherKeys, MatcherSettings, OrderAssets}
import com.wavesplatform.metrics.TimerExt
import com.wavesplatform.state._
import kamon.Kamon
import org.iq80.leveldb.DB
import scorex.account.Address
import scorex.transaction.AssetId
import scorex.transaction.assets.exchange.{Order, OrderType}

class OrderHistory(db: DB, settings: MatcherSettings) {
  import com.wavesplatform.matcher.MatcherKeys._

  private val timer               = Kamon.timer("matcher.order-history.impl")
  private val saveOpenVolumeTimer = timer.refine("action" -> "save-open-volume")
  private val saveOrderInfoTimer  = timer.refine("action" -> "save-order-info")
  private val openVolumeTimer     = timer.refine("action" -> "open-volume")

  private def combine(order: Order, curr: OrderInfo, diff: OrderInfoDiff): OrderInfo = {
    val r =
      if (diff.isNew) {
        val executedAmount = curr.filled + diff.addExecutedAmount.getOrElse(0L)
        val remainingFee   = order.matcherFee - diff.executedFee.getOrElse(0L)
        OrderInfo(
          amount = order.amount,
          filled = executedAmount,
          canceled = diff.nowCanceled.getOrElse(false),
          minAmount = diff.newMinAmount,
          remainingFee = remainingFee,
          unsafeTotalSpend = OrderInfo.safeSum(curr.totalSpend(LimitOrder(order)), diff.lastSpend.getOrElse(0L))
        )
      } else {
        OrderInfo(
          amount = order.amount,
          filled = curr.filled + diff.addExecutedAmount.getOrElse(0L),
          canceled = diff.nowCanceled.getOrElse(curr.canceled),
          minAmount = diff.newMinAmount.orElse(curr.minAmount),
          remainingFee = curr.remainingFee - diff.executedFee.getOrElse(0L),
          unsafeTotalSpend = OrderInfo.safeSum(curr.totalSpend(LimitOrder(order)), diff.lastSpend.getOrElse(0L))
        )
      }

    println(s"""
               |combine (order.id = ${order.id()}, sender = ${order.sender}):
               |  curr:     $curr
               |  diff:     $diff
               |  combined: $r
             """.stripMargin)
    r
  }

  private def saveOrderInfo(rw: RW, event: Event): Seq[Order] =
    saveOrderInfoTimer.measure(db.readWrite { rw =>
      val diff = Events.collectChanges(event)(x => DBUtils.orderInfoOpt(rw, x.id()).isEmpty)

      println(s"""
           |saveOrderInfo: ${diff.size} changes
           |""".stripMargin)
      val updatedInfo = diff.map {
        case (o, d) =>
          val orderId  = o.id()
          val curr     = DBUtils.orderInfo(rw, orderId)
          val combined = combine(o, curr, d)
          println(s"Saving new order info for $orderId: $combined")
          rw.put(MatcherKeys.orderInfo(orderId), combined)
          o
      }

      updatedInfo
    })

  def openVolume(address: Address, assetId: Option[AssetId]): Long =
    openVolumeTimer.measure(db.get(MatcherKeys.openVolume(address, assetId)).getOrElse(0L))

  private def toString(openPortfolio: OpenPortfolio): String =
    openPortfolio.orders
      .map { case (assetId, v) => s"  $assetId -> $v" }
      .mkString("\n")

  private def toString(eventDiff: Map[Address, OpenPortfolio]): String =
    eventDiff
      .map { case (addr, portfolio) => s"$addr:\n${toString(portfolio)}" }
      .mkString("\n")

  private def saveOpenVolume(rw: RW, opDiff: Map[Address, OpenPortfolio]): Unit = saveOpenVolumeTimer.measure {
    println(s"""|
                |saveOpenVolume: 
                |opDiff:
                |${toString(opDiff)}
                |""".stripMargin)

    for ((address, op) <- opDiff) {
      val newAssets = Set.newBuilder[Option[AssetId]]
      for ((assetId, amount) <- op.orders if amount != 0) {
        val k = MatcherKeys.openVolume(address, assetId)
        val orig = rw.get(k) match {
          case None =>
            newAssets += assetId
            0L
          case Some(v) => v
        }
        val newValue = safeSum(orig, amount)

        println(s"saveOpenVolume: update: $address: $assetId: $orig -> $newValue ($orig + $amount)")
        rw.put(k, Some(newValue))
      }

      val r = newAssets.result()
      if (r.nonEmpty) {
        val k         = openVolumeSeqNr(address)
        val prevSeqNr = rw.get(k)
        for ((assetId, offset) <- r.zipWithIndex) {
          rw.put(openVolumeAsset(address, prevSeqNr + offset + 1), assetId)
        }
        rw.put(k, prevSeqNr + r.size)
      }
    }
  }

  private def saveOrder(rw: RW, order: Order): Unit = rw.put(MatcherKeys.order(order.id()), Some(order))

  def orderAccepted(event: OrderAdded): Unit = db.readWrite { rw =>
    println(s"orderAccepted start: $event")
    val lo = event.order
    saveOrder(rw, lo.order)

    // OrderAccepted sended after matchOrder!
    println(s"orderAccepted for ${event.order.order.id()} prev order info: ${orderInfo(lo.order.id())}")
    val prevOrderInfo = {
      val x = orderInfo(lo.order.id())
      if (x == OrderInfo.empty) OrderInfo(lo.order.amount, 0L, false, Some(lo.minAmountOfAmountAsset), lo.order.matcherFee, 0L)
      else x
    }
    val newOrders        = saveOrderInfo(rw, event)
    val updatedOrderInfo = orderInfo(lo.order.id())

    //val opDiff          = Events.createOpenPortfolio(event)
    val opDiff = orderInfoDiffAccepted(
      lo.order,
      updatedOrderInfo
    )
    val opOrderInfoDiff = orderInfoDiffExecuted(lo.order, prevOrderInfo, updatedOrderInfo)
    println(s"""|
                |orderAccepted:
                |opDiff:
                |${toString(opDiff)}
                |
                |opOrderInfoDiff:
                |${toString(opOrderInfoDiff)}
                |
                |
                |""".stripMargin)
    saveOpenVolume(rw, opDiff)

    // for OrderAdded events, updatedInfo contains just one element
    for (o <- newOrders) {
      val k         = MatcherKeys.addressOrdersSeqNr(o.senderPublicKey)
      val nextSeqNr = rw.get(k) + 1
      rw.put(k, nextSeqNr)

      val spendAssetId = if (o.orderType == OrderType.BUY) o.assetPair.priceAsset else o.assetPair.amountAsset
      rw.put(MatcherKeys.addressOrders(o.senderPublicKey, nextSeqNr), Some(OrderAssets(o.id(), spendAssetId)))
    }

    println(s"orderAccepted end: $event")
  }

  def toString(x: OrderInfo): String = {
    s"""|$x
        |remaining: ${x.remaining}
        |remainingFee: ${x.remainingFee}""".stripMargin
  }

  def orderExecuted(event: OrderExecuted): Unit = db.readWrite { rw =>
    println(s"orderExecuted start: $event")
    saveOrder(rw, event.submitted.order)
    val prevCounterInfo = orderInfo(event.counter.order.id())
    saveOrderInfo(rw, event)
    val updatedCounterInfo   = orderInfo(event.counter.order.id())
    val updatedSubmittedInfo = orderInfo(event.submitted.order.id())

    val submittedStatus = updatedSubmittedInfo.status
    val submittedOpOrderInfoDiff = orderInfoDiffAccepted(
      event.submitted.order,
      updatedSubmittedInfo
    )
    val counterInfoDiff = orderInfoDiffExecuted(event.counter.order, prevCounterInfo, updatedCounterInfo)
    val opOrderInfoDiff = Monoid.combine(
      counterInfoDiff,
      if (submittedStatus.isFinal) Map.empty[Address, OpenPortfolio] else submittedOpOrderInfoDiff
    )
    println(s"""|
                |orderExecuted:
                |
                |opOrderInfoDiff:
                |${toString(opOrderInfoDiff)}
                |
                |submittedOpOrderInfoDiff:
                |${toString(submittedOpOrderInfoDiff)}
                |
                |counterInfoDiff:
                |${toString(counterInfoDiff)}
                |
                |prevCounterInfo:
                |${toString(prevCounterInfo)}
                |
                |updatedCounterInfo:
                |${toString(updatedCounterInfo)}
                |
                |updatedSubmittedInfo:
                |${toString(updatedSubmittedInfo)}
                |
                |event.counter (id=${event.counter.order.id()}): ${event.counter}
                |event.counterRemainingAmount: ${event.counterRemainingAmount}
                |event.counterRemainingFee: ${event.counterRemainingFee}
                |
                |counter status: ${updatedCounterInfo.status}
                |
                |event.submitted (id=${event.submitted.order.id()}): ${event.submitted}
                |event.submittedRemainingAmount: ${event.submittedRemainingAmount}
                |event.submittedRemainingFee: ${event.submittedRemainingFee}
                |
                |submitted status: $submittedStatus
                |""".stripMargin)

    saveOpenVolume(rw, opOrderInfoDiff)

    if (!submittedStatus.isFinal) {
      import event.submitted.{order => submittedOrder}
      val k         = MatcherKeys.addressOrdersSeqNr(submittedOrder.senderPublicKey)
      val nextSeqNr = rw.get(k) + 1
      rw.put(k, nextSeqNr)
      rw.put(MatcherKeys.addressOrders(submittedOrder.senderPublicKey, nextSeqNr), Some(OrderAssets(submittedOrder.id(), event.submitted.spentAsset)))
    }
    println(s"orderExecuted end: $event")
  }

  def orderCanceled(event: OrderCanceled): Unit = db.readWrite { rw =>
    println(s"orderCanceled start: $event")
    saveOrderInfo(rw, event) // !!
    val info = DBUtils.orderInfo(rw, event.limitOrder.order.id())
    val opDiff = orderInfoDiffCancel(
      event.limitOrder.order,
      info
    )
    println(s"""|
          |info: $info
          |opDiff: $opDiff
          |""".stripMargin)
    saveOpenVolume(rw, opDiff)
    println(s"orderCanceled end: $event")
  }

  def orderInfo(id: ByteStr): OrderInfo = DBUtils.orderInfo(db, id)

  def order(id: ByteStr): Option[Order] = db.get(MatcherKeys.order(id))

  def deleteOrder(address: Address, orderId: ByteStr): Boolean = db.readWrite { rw =>
    DBUtils.orderInfo(rw, orderId).status match {
      case Filled(_) | LimitOrder.Cancelled(_) =>
        rw.delete(MatcherKeys.order(orderId))
        rw.delete(MatcherKeys.orderInfo(orderId))
        true
      case _ =>
        false
    }
  }
}

object OrderHistory {
  import OrderInfo.orderStatusOrdering

  object OrderHistoryOrdering extends Ordering[(ByteStr, OrderInfo, Option[Order])] {
    def orderBy(oh: (ByteStr, OrderInfo, Option[Order])): (OrderStatus, Long) = (oh._2.status, -oh._3.map(_.timestamp).getOrElse(0L))

    override def compare(first: (ByteStr, OrderInfo, Option[Order]), second: (ByteStr, OrderInfo, Option[Order])): Int = {
      implicitly[Ordering[(OrderStatus, Long)]].compare(orderBy(first), orderBy(second))
    }
  }
}
