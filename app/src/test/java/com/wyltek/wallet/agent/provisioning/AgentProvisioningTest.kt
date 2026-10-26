package com.wyltek.wallet.agent.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PIN = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

class AgentProvisioningTest {

    @Test
    fun `bundle is compact json carrying device_id token service_name and tls_pin`() {
        val out = buildProvisioningBundle("dev-123", "tok-abc", PIN)
        assertEquals("""{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123","tls_pin":"$PIN"}""", out)
    }

    @Test
    fun `bundle contains the POS substring markers and no space after colons`() {
        val out = buildProvisioningBundle("id", "t", PIN)!!
        assertTrue(out.contains("\"device_id\":\""))
        assertTrue(out.contains("\"token\":\""))
        assertTrue(out.contains("\"tls_pin\":\""))
        assertFalse("must be compact (no space after colon)", out.contains("\": \""))
    }

    @Test
    fun `long url-safe base64 token survives intact and untruncated`() {
        val token = "A".repeat(700) + "_-="
        val out = buildProvisioningBundle("11111111-2222-3333-4444-555555555555", token, PIN)!!
        assertTrue("token must appear whole", out.contains(token))
        assertTrue("must end on closing quote+brace", out.endsWith("\"}"))
    }

    @Test
    fun `null deviceId returns null (unpaired guard)`() {
        assertNull(buildProvisioningBundle(null, "tok", PIN))
    }

    @Test
    fun `blank deviceId returns null (unpaired guard)`() {
        assertNull(buildProvisioningBundle("   ", "tok", PIN))
    }

    @Test
    fun `bundle carries k_debit and merchant_id after tls_pin`() {
        val out = buildProvisioningBundle("dev-123", "tok-abc", PIN, kDebitHex = "00112233445566778899aabbccddeeff", merchantIdHex = "1122334455667788")
        assertEquals(
            """{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123","tls_pin":"$PIN","k_debit":"00112233445566778899aabbccddeeff","merchant_id":"1122334455667788"}""",
            out
        )
    }

    @Test
    fun `bundle without prepaid fields is the three-field bundle plus tls_pin`() {
        assertEquals(
            """{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123","tls_pin":"$PIN"}""",
            buildProvisioningBundle("dev-123", "tok-abc", PIN)
        )
        assertEquals(
            buildProvisioningBundle("dev-123", "tok-abc", PIN),
            buildProvisioningBundle("dev-123", "tok-abc", PIN, kDebitHex = "aa", merchantIdHex = null)
        )
    }

    @Test
    fun `k_debit travels only with a token minted with prepaid_settle`() {
        val k = "11".repeat(16); val m = "22".repeat(8)
        val (k1, m1) = prepaidBundleFields(mintedWithPrepaid = false, kDebitHex = k, merchantIdHex = m)
        assertNull(k1); assertNull(m1)
        val plain = buildProvisioningBundle("dev-1", "tok", PIN, k1, m1)!!
        assertFalse(plain.contains("k_debit")); assertFalse(plain.contains(k))
        val (k2, m2) = prepaidBundleFields(mintedWithPrepaid = true, kDebitHex = k, merchantIdHex = m)
        assertTrue(buildProvisioningBundle("dev-1", "tok", PIN, k2, m2)!!.contains("\"k_debit\":\"$k\""))
    }

    @Test
    fun `bundle carries tls_pin immediately after service_name and before k_debit`() {
        val b = buildProvisioningBundle("abc12345-x", "TOK", PIN, "00".repeat(16), "11".repeat(8))!!
        val i = b.indexOf("\"service_name\":\"")
        val p = b.indexOf("\"tls_pin\":\"$PIN\"")
        val k = b.indexOf("\"k_debit\":\"")
        assertTrue(i in 0 until p)
        assertTrue(p < k)
    }

    @Test
    fun `three-field bundle still carries tls_pin`() {
        assertTrue(buildProvisioningBundle("abc12345-x", "TOK", PIN)!!.contains("\"tls_pin\":\"$PIN\"}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `uppercase pin is refused`() {
        buildProvisioningBundle("abc12345-x", "TOK", PIN.uppercase())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `63-char pin is refused`() {
        buildProvisioningBundle("abc12345-x", "TOK", PIN.dropLast(1))
    }

    @Test
    fun `pinPrefix is the first eight hex`() {
        assertEquals(PIN.take(8), pinPrefix(PIN))
    }
}
