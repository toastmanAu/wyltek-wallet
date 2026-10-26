package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.server.AgentTls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGatewayNoticeTest {
    @Test fun `regeneration sets the notice and dismiss clears it`() {
        val g = AgentGatewayNotices()
        assertNull(g.tlsNotice)
        g.noteTlsRegenerated()
        assertEquals(AgentTls.NOTICE_REGENERATED, g.tlsNotice)
        g.dismissTlsNotice()
        assertNull(g.tlsNotice)
    }

    @Test fun `a persisted marker seeds the notice and dismiss clears the marker`() {
        var cleared = false
        val g = AgentGatewayNotices(markerExists = { true }, clearMarker = { cleared = true })
        assertEquals(AgentTls.NOTICE_REGENERATED, g.tlsNotice)
        g.dismissTlsNotice()
        assertNull(g.tlsNotice)
        assertTrue(cleared)
    }

    @Test fun `no marker means no notice`() {
        assertNull(AgentGatewayNotices(markerExists = { false }, clearMarker = {}).tlsNotice)
    }
}
