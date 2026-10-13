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

    @Test
    fun `bundle carries k_debit and merchant_id after the original three fields`() {
        val out = buildProvisioningBundle("dev-123", "tok-abc", kDebitHex = "00112233445566778899aabbccddeeff", merchantIdHex = "1122334455667788")
        assertEquals("""{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123","k_debit":"00112233445566778899aabbccddeeff","merchant_id":"1122334455667788"}""", out)
    }

    @Test
    fun `bundle without prepaid fields is byte-identical to before`() {
        assertEquals("""{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123"}""", buildProvisioningBundle("dev-123", "tok-abc"))
        assertEquals(buildProvisioningBundle("dev-123", "tok-abc"), buildProvisioningBundle("dev-123", "tok-abc", kDebitHex = "aa", merchantIdHex = null))
    }

    @Test
    fun `k_debit travels only with a token minted with prepaid_settle`() {
        val k = "11".repeat(16); val m = "22".repeat(8)
        val (k1, m1) = prepaidBundleFields(mintedWithPrepaid = false, kDebitHex = k, merchantIdHex = m)
        assertNull(k1); assertNull(m1)
        val plain = buildProvisioningBundle("dev-1", "tok", k1, m1)!!
        assertFalse(plain.contains("k_debit")); assertFalse(plain.contains(k))
        val (k2, m2) = prepaidBundleFields(mintedWithPrepaid = true, kDebitHex = k, merchantIdHex = m)
        assertTrue(buildProvisioningBundle("dev-1", "tok", k2, m2)!!.contains("\"k_debit\":\"$k\""))
    }
}
