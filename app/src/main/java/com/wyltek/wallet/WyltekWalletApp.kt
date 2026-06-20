package com.wyltek.wallet

import android.app.Application
import com.wyltek.wallet.agent.AgentGateway

class WyltekWalletApp : Application() {
    val agentGateway: AgentGateway by lazy { AgentGateway(this) }
    override fun onCreate() { super.onCreate() }
}
