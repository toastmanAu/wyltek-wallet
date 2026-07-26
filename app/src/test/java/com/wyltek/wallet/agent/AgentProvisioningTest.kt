package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.provisioning.buildProvisioningBundle
import com.wyltek.wallet.agent.provisioning.serviceNameFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProvisioningTest {
    @Test fun `serviceName is blackbox plus 8 hex of deviceId`() {
        assertEquals("blackbox-1a2b3c4d", serviceNameFor("1a2b3c4d-ffff-0000-1111-222233334444"))
    }
    @Test fun `bundle carries device_id token and service_name`() {
        val b = buildProvisioningBundle("1a2b3c4d-ffff", "biscuit-b64")!!
        assertTrue(b.contains(""""device_id":"1a2b3c4d-ffff""""))
        assertTrue(b.contains(""""token":"biscuit-b64""""))
        assertTrue(b.contains(""""service_name":"blackbox-1a2b3c4d""""))
    }
    @Test fun `blank deviceId yields null bundle`() {
        assertNull(buildProvisioningBundle("", "t"))
        assertNull(buildProvisioningBundle("   ", "t"))
    }
}
