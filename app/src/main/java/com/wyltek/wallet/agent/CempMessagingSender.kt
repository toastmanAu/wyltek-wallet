package com.wyltek.wallet.agent

import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.data.WalletRepository
import com.wyltek.wallet.data.WalletResult

/**
 * Fulfills Plan B1's [MessagingSender] by sending an on-chain CEMP-PQ message.
 *
 * The agent supplies the message plaintext as a hex string via [Intent.action].
 * [amount] is ignored — CEMP messages carry no CKB transfer (capacity is sized
 * from the message data, not from an explicit amount).
 *
 * Returns [WalletResult.Error] if [action] is null (no payload) or if
 * [WalletRepository.sendCempMessage] fails.
 */
class CempMessagingSender(private val repository: WalletRepository) : MessagingSender {
    override suspend fun send(
        account: WalletAccount,
        to: String,
        amount: Long,
        action: String?
    ): WalletResult<String> {
        val plaintextHex = action
            ?: return WalletResult.Error("messaging intent missing payload (action)")
        return repository.sendCempMessage(account, to, plaintextHex)
    }
}
