package api

import controller.Controller
import database.PrunableBlockchainStorage
import play.api.libs.json.{JsValue, JsObject}
import scorex.account.Account
import scorex.block.Block
import scorex.crypto.{Crypto, Base58}
import scorex.wallet.Wallet
import scala.util.Try

trait CommonApifunctions {

  protected[api] def walletNotExistsOrLocked(): Option[JsObject] =
    if (!Wallet.isUnlocked()) {
      Some(ApiError.toJson(ApiError.ERROR_WALLET_LOCKED))
    } else None

  protected[api] def walletExists(): Option[JsObject] =
    if (Wallet.isUnlocked()) {
      Some(ApiError.toJson(ApiError.ERROR_WALLET_ALREADY_EXISTS))
    } else None

  protected[api] def withBlock(encodedSignature: String)(action: Block => JsValue): JsValue =
    Try {
      Base58.decode(encodedSignature)
    }.toOption.map { signature =>
      PrunableBlockchainStorage.blockByHeader(signature) match {
        case Some(block) => action(block)
        case None => ApiError.toJson(ApiError.ERROR_BLOCK_NO_EXISTS)
      }
    }.getOrElse(ApiError.toJson(ApiError.ERROR_INVALID_SIGNATURE))

  protected[api] def withAccount(address: String)(action: Account => JsValue): JsValue =
    walletNotExistsOrLocked().getOrElse {
      if (!Crypto.isValidAddress(address)) {
        ApiError.toJson(ApiError.ERROR_INVALID_ADDRESS)
      } else {
        Wallet.privateKeyAccount(address) match {
          case None => ApiError.toJson(ApiError.ERROR_WALLET_ADDRESS_NO_EXISTS)
          case Some(account) => action(account)
        }
      }
    }
}
