package com.wyltek.wallet.prepaid

import org.junit.Assert.assertEquals
import org.junit.Test

class PrepaidHexTest {
    @Test fun ensure0x_adds_the_prefix_and_lowercases() {
        assertEquals("0xabcd", ensure0x("abcd"))
        assertEquals("0xabcd", ensure0x("ABcd"))
        assertEquals("0xabcd", ensure0x("0xABCD"))
        assertEquals("0xabcd", ensure0x("0XabCD"))
        assertEquals("0x", ensure0x(""))
    }
    @Test fun ensure0x_is_idempotent() {
        val h = "0x" + "ab".repeat(32)
        assertEquals(h, ensure0x(ensure0x(h)))
    }
    @Test fun normalizeOutpoint_normalises_only_the_hash() {
        assertEquals("0x" + "ab".repeat(32) + ":1", normalizeOutpoint("AB".repeat(32) + ":1"))
        assertEquals("0xab:12", normalizeOutpoint("0xab:12"))
    }
}
