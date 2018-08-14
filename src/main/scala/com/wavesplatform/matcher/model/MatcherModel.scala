package com.wavesplatform.matcher.model

import cats.Monoid
import cats.implicits._
import com.wavesplatform.matcher.model.MatcherModel.Price
import com.wavesplatform.state.{ByteStr, Portfolio}
import play.api.libs.json.{JsObject, JsValue, Json}
import scorex.account.Address
import scorex.transaction.assets.exchange._
import scorex.transaction.{AssetAcc, AssetId}

import scala.math.BigDecimal.RoundingMode

object MatcherModel {
  type Price     = Long
  type Level[+A] = Vector[A]
  type OrderId   = String
}

case class LevelAgg(price: Long, amount: Long)

sealed trait LimitOrder {
  def price: Price
  def amount: Long // remaining
  def fee: Long    // remaining
  def order: Order
  def partial(amount: Long, fee: Long): LimitOrder

  def getRawSpendAmount: Long // Without correction
  def getSpendAmount: Long
  def getReceiveAmount: Long

  def spentAcc: AssetAcc = AssetAcc(order.senderPublicKey, order.getSpendAssetId)
  def rcvAcc: AssetAcc   = AssetAcc(order.senderPublicKey, order.getReceiveAssetId)
  def feeAcc: AssetAcc   = AssetAcc(order.senderPublicKey, None)

  def spentAsset: Option[ByteStr] = order.getSpendAssetId
  def rcvAsset: Option[ByteStr]   = order.getReceiveAssetId
  def feeAsset: Option[ByteStr]   = None

  def minAmountOfAmountAsset: Long         = minimalAmountOfAmountAssetByPrice(price)
  def amountOfPriceAsset: Long             = (BigDecimal(amount) * price / Order.PriceConstant).setScale(0, RoundingMode.FLOOR).toLong
  def amountOfAmountAsset: Long            = correctedAmountOfAmountAsset(price, amount)
  def executionAmount(o: LimitOrder): Long = correctedAmountOfAmountAsset(o.price, amount)

  def isValid: Boolean =
    amount > 0 && amount >= minAmountOfAmountAsset && amount < Order.MaxAmount && getSpendAmount > 0 && getReceiveAmount > 0

  protected def minimalAmountOfAmountAssetByPrice(p: Long): Long = (BigDecimal(Order.PriceConstant) / p).setScale(0, RoundingMode.CEILING).toLong
  protected def correctedAmountOfAmountAsset(p: Long, a: Long): Long = {
    val settledTotal = (BigDecimal(p) * a / Order.PriceConstant).setScale(0, RoundingMode.FLOOR).toLong
    (BigDecimal(settledTotal) / p * Order.PriceConstant).setScale(0, RoundingMode.CEILING).toLong
  }
}

case class BuyLimitOrder(price: Price, amount: Long, fee: Long, order: Order) extends LimitOrder {
  def partial(amount: Long, fee: Long): LimitOrder = copy(amount = amount, fee = fee)
  def getReceiveAmount: Long                       = amountOfAmountAsset
  def getSpendAmount: Long                         = amountOfPriceAsset
  def getRawSpendAmount: Long                      = amountOfPriceAsset
}

case class SellLimitOrder(price: Price, amount: Long, fee: Long, order: Order) extends LimitOrder {
  def partial(amount: Long, fee: Long): LimitOrder = copy(amount = amount, fee = fee)
  def getReceiveAmount: Long                       = amountOfPriceAsset
  def getSpendAmount: Long                         = amountOfAmountAsset
  def getRawSpendAmount: Long                      = amount
}

object LimitOrder {
  sealed trait OrderStatus {
    def name: String
    def json: JsValue
    def isFinal: Boolean
    def ordering: Int
  }

  case object Accepted extends OrderStatus {
    val name             = "Accepted"
    def json: JsObject   = Json.obj("status" -> name)
    val isFinal: Boolean = false
    val ordering         = 1
  }
  case object NotFound extends OrderStatus {
    val name             = "NotFound"
    def json: JsObject   = Json.obj("status" -> name)
    val isFinal: Boolean = true
    val ordering         = 5
  }
  case class PartiallyFilled(filled: Long) extends OrderStatus {
    val name             = "PartiallyFilled"
    def json: JsObject   = Json.obj("status" -> name, "filledAmount" -> filled)
    val isFinal: Boolean = false
    val ordering         = 1
  }
  case class Filled(filled: Long) extends OrderStatus {
    val name             = "Filled"
    def json: JsObject   = Json.obj("status" -> name, "filledAmount" -> filled)
    val isFinal: Boolean = true
    val ordering         = 3
  }
  case class Cancelled(filled: Long) extends OrderStatus {
    val name             = "Cancelled"
    def json: JsObject   = Json.obj("status" -> name, "filledAmount" -> filled)
    val isFinal: Boolean = true
    val ordering         = 3
  }

  def apply(o: Order): LimitOrder = {
    val partialFee = getPartialFee(o.matcherFee, o.amount, o.amount)
    println(s"MatcherModel: LimitOrder.apply(${o.sender}): partialFee: $partialFee")
    o.orderType match {
      case OrderType.BUY  => BuyLimitOrder(o.price, o.amount, partialFee, o)
      case OrderType.SELL => SellLimitOrder(o.price, o.amount, partialFee, o)
    }
  }

  def limitOrder(price: Long, remainingAmount: Long, remainingFee: Long, o: Order): LimitOrder = {
    o.orderType match {
      case OrderType.BUY  => BuyLimitOrder(price, remainingAmount, remainingFee, o)
      case OrderType.SELL => SellLimitOrder(price, remainingAmount, remainingFee, o)
    }
  }

  def getPartialFee(matcherFee: Long, totalAmount: Long, partialAmount: Long): Long = {
    // Should not round! It could lead to forks. See ExchangeTransactionDiff
    (BigInt(matcherFee) * partialAmount / totalAmount).toLong
  }
}

object Events {

  sealed trait Event

  case class OrderExecuted(submitted: LimitOrder, counter: LimitOrder) extends Event {
    def executedAmount: Long = math.min(submitted.executionAmount(counter), counter.amountOfAmountAsset)

    def counterRemainingAmount: Long = math.max(counter.amount - executedAmount, 0)
    def counterExecutedFee: Long     = LimitOrder.getPartialFee(counter.order.matcherFee, counter.order.amount, executedAmount)
    def counterRemainingFee: Long    = math.max(counter.fee - counterExecutedFee, 0)
    def counterExecuted: LimitOrder  = counter.partial(amount = executedAmount, fee = counterExecutedFee)
    def counterRemaining: LimitOrder = counter.partial(amount = counterRemainingAmount, fee = counterRemainingFee)

    def submittedRemainingAmount: Long = math.max(submitted.amount - executedAmount, 0)
    def submittedExecutedFee: Long     = LimitOrder.getPartialFee(submitted.order.matcherFee, submitted.order.amount, executedAmount)
    def submittedRemainingFee: Long    = math.max(submitted.fee - submittedExecutedFee, 0)
    def submittedExecuted: LimitOrder  = submitted.partial(amount = executedAmount, fee = submittedExecutedFee)
    def submittedRemaining: LimitOrder = submitted.partial(amount = submittedRemainingAmount, fee = submittedRemainingFee)
  }

  case class OrderAdded(order: LimitOrder) extends Event

  case class OrderCanceled(limitOrder: LimitOrder, unmatchable: Boolean) extends Event

  case class ExchangeTransactionCreated(tx: ExchangeTransaction)

  case class BalanceChanged(changes: Map[Address, BalanceChanged.Changes]) {
    def isEmpty: Boolean = changes.isEmpty
  }

  object BalanceChanged {
    val empty: BalanceChanged = BalanceChanged(Map.empty)
    case class Changes(updatedPortfolio: Portfolio, changedAssets: Set[Option[AssetId]])
  }

  def collectChanges(event: Event)(isNew: Order => Boolean): Seq[(Order, OrderInfoDiff)] = {
    event match {
      case OrderAdded(lo) =>
        Seq((lo.order, OrderInfoDiff(isNew = isNew(lo.order), newMinAmount = Some(lo.minAmountOfAmountAsset))))
      case oe: OrderExecuted =>
        val submitted = oe.submittedExecuted
        val counter   = oe.counterExecuted
        println(s"""
                   |collectChanges (from Event):
                   |submitted (id=${oe.submitted.order.id()}) executionAmount(counter): ${oe.submitted.executionAmount(oe.counter)}
                   |submitted.amount: ${submitted.amount}/${submitted.order.amount}
                   |submitted.amountOfAmountAsset: ${submitted.amountOfAmountAsset}
                   |submitted.amountOfPriceAsset: ${submitted.amountOfPriceAsset}
                   |submitted fee: ${submitted.fee}/${submitted.order.matcherFee}
                   |submittedRemaining.getSpendAmount: ${oe.submittedRemaining.getSpendAmount}
                   |
                   |counter (id=${oe.counter.order.id()}) amountOfAmountAsset: ${oe.counter.amountOfAmountAsset}
                   |counter.amount: ${counter.amount}/${counter.order.amount}
                   |counter.amountOfAmountAsset: ${counter.amountOfAmountAsset}
                   |counter.amountOfPriceAsset: ${counter.amountOfPriceAsset}
                   |counterRemaining.getSpendAmount: ${oe.counterRemaining.getSpendAmount}
                   |counter fee: ${counter.fee}/${counter.order.matcherFee}
                   |""".stripMargin)
        Seq(
          (submitted.order,
           OrderInfoDiff(
             isNew = isNew(submitted.order),
             addExecutedAmount = Some(oe.executedAmount),
             executedFee = Some(submitted.fee),
             newMinAmount = Some(submitted.minAmountOfAmountAsset),
             lastSpend = Some(submitted.getSpendAmount)
           )),
          (counter.order,
           OrderInfoDiff(
             isNew = isNew(counter.order),
             addExecutedAmount = Some(oe.executedAmount),
             executedFee = Some(counter.fee),
             newMinAmount = Some(counter.minAmountOfAmountAsset),
             lastSpend = Some(counter.getSpendAmount)
           ))
        )
      case OrderCanceled(lo, unmatchable) =>
        // The order should not have Cancelled status, if it was cancelled by unmatchable amounts
        Seq((lo.order, OrderInfoDiff(nowCanceled = Some(!unmatchable))))
    }
  }

  private def releaseFee(totalReceiveAmount: Long, matcherFee: Long, prevRemaining: Long, updatedRemaining: Long): Long = {
    val executedBefore = matcherFee - prevRemaining
    val restReserved   = math.max(matcherFee - totalReceiveAmount - executedBefore, 0L)

    val executed = prevRemaining - updatedRemaining
    println(s"""|
            |releaseFee:
            |totalReceiveAmount: $totalReceiveAmount
            |matcherFee: $matcherFee
            |prevRemaining: $prevRemaining
            |executedBefore: $executedBefore
            |restReserved: $restReserved
            |executed: $executed
            |""".stripMargin)
    math.min(executed, restReserved)
  }

  private def releaseFee(order: Order, prevRemaining: Long, updatedRemaining: Long): Long = {
    val lo = LimitOrder(order)
    if (lo.rcvAsset == lo.feeAsset) releaseFee(lo.getReceiveAmount, order.matcherFee, prevRemaining, updatedRemaining)
    else prevRemaining - updatedRemaining
  }

  def orderInfoDiffAccepted(order: Order, newOrderInfo: OrderInfo): Map[Address, OpenPortfolio] = {
    val lo             = LimitOrder(order)
    val maxSpendAmount = lo.getRawSpendAmount
    val remainingSpend = maxSpendAmount - newOrderInfo.totalSpend(lo)
    val remainingFee   = if (lo.feeAcc == lo.rcvAcc) math.max(newOrderInfo.remainingFee - lo.getReceiveAmount, 0L) else newOrderInfo.remainingFee

    println(s"orderInfoDiffNew: remaining spend=$remainingSpend, remaining fee=$remainingFee")
    Map(
      order.sender.toAddress -> OpenPortfolio(
        Monoid.combine(
          Map(order.getSpendAssetId -> remainingSpend),
          Map(lo.feeAsset           -> remainingFee)
        )
      )
    )
  }

  def orderInfoDiffExecuted(order: Order, prev: OrderInfo, updated: OrderInfo): Map[Address, OpenPortfolio] = {
    val lo           = LimitOrder(order)
    val changedSpend = prev.totalSpend(lo) - updated.totalSpend(lo)
    val changedFee   = -releaseFee(order, prev.remainingFee, updated.remainingFee)
    println(s"orderInfoDiff: changed spend=$changedSpend, fee=$changedFee")
    Map(
      order.sender.toAddress -> OpenPortfolio(
        Monoid.combine(
          Map(order.getSpendAssetId -> changedSpend),
          Map(lo.feeAsset           -> changedFee)
        )
      )
    )
  }

  def orderInfoDiffCancel(order: Order, curr: OrderInfo): Map[Address, OpenPortfolio] = {
    val lo             = LimitOrder(order)
    val maxSpendAmount = lo.getRawSpendAmount
    val remainingSpend = curr.totalSpend(lo) - maxSpendAmount
    val remainingFee   = -releaseFee(order, curr.remainingFee, 0)

    println(s"orderInfoDiffCancel: remaining spend=$remainingSpend, remaining fee=$remainingFee")
    Map(
      order.sender.toAddress -> OpenPortfolio(
        Monoid.combine(
          Map(order.getSpendAssetId -> remainingSpend),
          Map(lo.feeAsset           -> remainingFee)
        )
      )
    )
  }
}
