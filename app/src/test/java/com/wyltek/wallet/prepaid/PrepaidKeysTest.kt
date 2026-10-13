package com.wyltek.wallet.prepaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrepaidKeysTest {
    private val blobs = mutableMapOf<String, ByteArray>()
    private var draws = 0
    private fun keys() = PrepaidKeys(load = { blobs[it] }, store = { k, v -> blobs[k] = v }, random = { draws++; ByteArray(16) { (it + 1).toByte() } })

    @Test fun `generates once and is stable`() {
        val k = keys()
        assertFalse(k.hasKDebit())
        val a = k.kDebitHex()
        assertEquals("0102030405060708090a0b0c0d0e0f10", a)
        assertTrue(k.hasKDebit())
        assertEquals(a, keys().kDebitHex())
        assertEquals(1, draws)
    }

    @Test fun `rejects a stored blob of the wrong length`() {
        blobs[PrepaidKeys.KEY_K_DEBIT] = ByteArray(15)
        try { keys().kDebitHex(); fail() } catch (e: IllegalStateException) {}
    }
}
