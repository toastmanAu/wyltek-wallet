package com.wyltek.wallet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendReceiptTest {
    private val hash = "0x" + "ab".repeat(32)

    @Test fun `explorer url keeps a single 0x prefix`() {
        assertEquals("https://testnet.explorer.nervos.org/transaction/$hash",
            SendReceipt.explorerTxUrl("https://testnet.explorer.nervos.org", hash))
        assertEquals("https://testnet.explorer.nervos.org/transaction/$hash",
            SendReceipt.explorerTxUrl("https://testnet.explorer.nervos.org/", hash.removePrefix("0x").uppercase()))
    }

    @Test fun `status labels and terminal states`() {
        assertEquals("Checking…", SendReceipt.statusLabel(null))
        assertEquals("Pending", SendReceipt.statusLabel("pending"))
        assertEquals("Confirmed", SendReceipt.statusLabel("confirmed"))
        assertEquals("Rejected", SendReceipt.statusLabel("rejected"))
        assertEquals("Not yet seen by the node", SendReceipt.statusLabel("unknown"))
        assertTrue(SendReceipt.isFinal("confirmed"))
        assertTrue(SendReceipt.isFinal("rejected"))
        assertFalse(SendReceipt.isFinal("pending"))
        assertFalse(SendReceipt.isFinal(null))
    }

    @Test fun `short address keeps both ends`() {
        val addr = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqwgx292hnvmn68xf779vmzrshpmm6epn4c0cgwga"
        assertEquals("ckt1qzda0cr08m…n4c0cgwga", SendReceipt.shortAddress(addr))
        assertEquals("ckt1short", SendReceipt.shortAddress("ckt1short"))
    }
}
