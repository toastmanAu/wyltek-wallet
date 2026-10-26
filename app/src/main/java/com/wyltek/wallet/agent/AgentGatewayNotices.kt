package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.server.AgentTls

/**
 * Context-free holder for the dismissible "TLS key regenerated" notice, so it is host-JVM testable.
 * The notice is also persisted as a marker file (see AgentTls.regenMarker) so it survives the
 * process dying before the Agent screen has shown it: [markerExists] seeds it, [clearMarker]
 * runs on dismiss.
 */
class AgentGatewayNotices(
    markerExists: () -> Boolean = { false },
    private val clearMarker: () -> Unit = {},
) {
    @Volatile var tlsNotice: String? = if (markerExists()) AgentTls.NOTICE_REGENERATED else null
        private set

    fun noteTlsRegenerated() { tlsNotice = AgentTls.NOTICE_REGENERATED }

    fun dismissTlsNotice() {
        tlsNotice = null
        clearMarker()
    }
}
