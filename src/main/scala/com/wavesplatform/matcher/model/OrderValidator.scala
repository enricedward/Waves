package com.wavesplatform.matcher.model

import cats.implicits._
import com.wavesplatform.matcher.MatcherSettings
import com.wavesplatform.matcher.market.OrderBookActor.CancelOrder
import com.wavesplatform.metrics.TimerExt
import com.wavesplatform.state._
import com.wavesplatform.utx.UtxPool
import kamon.Kamon
import scorex.account.PublicKeyAccount
import scorex.transaction.AssetAcc
import scorex.transaction.ValidationError.GenericError
import scorex.transaction.assets.exchange.Validation.booleanOperators
import scorex.transaction.assets.exchange.{Order, Validation}
import scorex.utils.NTP
import scorex.wallet.Wallet

trait OrderValidator {
  val orderHistory: OrderHistory
  val utxPool: UtxPool
  val settings: MatcherSettings
  val wallet: Wallet

  lazy val matcherPubKey: PublicKeyAccount = wallet.findPrivateKey(settings.account).explicitGet()
  val MinExpiration                        = 60 * 1000L

  private val timer = Kamon.timer("matcher.validation")

  private def isBalanceWithOpenOrdersEnough(order: Order): Validation = {
    val lo = LimitOrder(order)

    val b: Map[Option[ByteStr], Long] = Seq(lo.spentAcc, lo.feeAcc).map(a => a.assetId -> spendableBalance(a)).toMap
    val newOrder = Events
      .orderInfoDiffAccepted(
        lo.order,
        OrderInfo(order.amount, 0L, canceled = false, None, order.matcherFee, 0L)
      )
      .getOrElse(order.senderPublicKey, OpenPortfolio.empty)

    val open  = b.keySet.map(id => id -> orderHistory.openVolume(order.senderPublicKey, id)).toMap
    val needs = OpenPortfolio(open).combine(newOrder)

    val res: Boolean = b.combine(needs.orders.mapValues(-_)).forall(_._2 >= 0)

    res :| s"Not enough tradable balance: ${b.combine(open.mapValues(-_))}, needs: $newOrder"
  }

  def getTradableBalance(acc: AssetAcc): Long = timer.refine("action" -> "tradableBalance").measure {
    math.max(0l, spendableBalance(acc) - orderHistory.openVolume(acc.account, acc.assetId))
  }

  def validateNewOrder(order: Order): Either[GenericError, Order] =
    timer
      .refine("action" -> "place", "pair" -> order.assetPair.toString)
      .measure {
        val v =
          (order.matcherPublicKey == matcherPubKey) :| "Incorrect matcher public key" &&
            (order.expiration > NTP.correctedTime() + MinExpiration) :| "Order expiration should be > 1 min" &&
            order.signaturesValid().isRight :| "signature should be valid" &&
            order.isValid(NTP.correctedTime()) &&
            (order.matcherFee >= settings.minOrderFee) :| s"Order matcherFee should be >= ${settings.minOrderFee}" &&
            (orderHistory.orderInfo(order.id()).status == LimitOrder.NotFound) :| "Order is already accepted" &&
            isBalanceWithOpenOrdersEnough(order)
        Either
          .cond(v, order, GenericError(v.messages()))
      }

  def validateCancelOrder(cancel: CancelOrder): Either[GenericError, CancelOrder] = {
    timer
      .refine("action" -> "cancel", "pair" -> cancel.assetPair.toString)
      .measure {
        val status = orderHistory.orderInfo(cancel.orderId).status
        val v = status match {
          case LimitOrder.NotFound  => Validation.failure("Order not found")
          case LimitOrder.Filled(_) => Validation.failure("Order is already Filled")
          case _ =>
            orderHistory
              .order(cancel.orderId)
              .fold(false)(_.senderPublicKey == cancel.req.senderPublicKey) :| "Order not found"
        }

        Either
          .cond(
            v,
            cancel,
            GenericError(v.messages())
          )
      }

  }

  private def spendableBalance(a: AssetAcc): Long = {
    val portfolio = utxPool.portfolio(a.account)
    a.assetId match {
      case Some(x) => portfolio.assets.getOrElse(x, 0)
      case None    => portfolio.spendableBalance
    }
  }
}
