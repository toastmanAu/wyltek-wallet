package com.wyltek.wallet.agent

import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.data.WalletResult

/** On-chain message send on the wallet's behalf. v1 impl is Unsupported; Plan B-CEMP fulfills it. */
interface MessagingSender {
    suspend fun send(account: WalletAccount, to: String, amount: Long, action: String?): WalletResult<String>
}

class UnsupportedMessagingSender : MessagingSender {
    override suspend fun send(account: WalletAccount, to: String, amount: Long, action: String?): WalletResult<String> =
        WalletResult.Error("on-chain messaging not yet available (Plan B-CEMP)")
}
