package com.wyltek.wallet.agent.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProvisioningTest {

    @Test
    fun `bundle is compact json carrying device_id token and service_name`() {
        val out = buildProvisioningBundle("dev-123", "tok-abc")
        assertEquals("""{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123"}""", out)
    }

    @Test
    fun `bundle contains the POS substring markers and no space after colons`() {
        val out = buildProvisioningBundle("id", "t")!!
        assertTrue(out.contains("\"device_id\":\""))
        assertTrue(out.contains("\"token\":\""))
        assertFalse("must be compact (no space after colon)", out.contains("\": \""))
    }

    @Test
    fun `long url-safe base64 token survives intact and untruncated`() {
        val token = "A".repeat(700) + "_-="
        val out = buildProvisioningBundle("11111111-2222-3333-4444-555555555555", token)!!
        assertTrue("token must appear whole", out.contains(token))
        assertTrue("must end on closing quote+brace", out.endsWith("\"}"))
    }

    @Test
    fun `null deviceId returns null (unpaired guard)`() {
        assertNull(buildProvisioningBundle(null, "tok"))
    }

    @Test
    fun `blank deviceId returns null (unpaired guard)`() {
        assertNull(buildProvisioningBundle("   ", "tok"))
    }
}
