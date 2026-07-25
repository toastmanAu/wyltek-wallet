package com.wyltek.wallet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RelayIntentIdTest {
    @Test fun `deterministic per token and nonce`() {
        assertEquals(relayIntentId("tok", "n1"), relayIntentId("tok", "n1"))
    }
    @Test fun `differs on nonce`() {
        assertNotEquals(relayIntentId("tok", "n1"), relayIntentId("tok", "n2"))
    }
    @Test fun `differs on token`() {
        assertNotEquals(relayIntentId("tokA", "n1"), relayIntentId("tokB", "n1"))
    }
    @Test fun `is 64-char lowercase hex`() {
        val id = relayIntentId("tok", "n1")
        assertEquals(64, id.length)
        assertEquals(id.lowercase(), id)
        assert(id.all { it in "0123456789abcdef" })
    }
    @Test fun `separator prevents token-nonce boundary collision`() {
        // ("ab","c") vs ("a","bc") must not collide thanks to the 0x00 separator
        assertNotEquals(relayIntentId("ab", "c"), relayIntentId("a", "bc"))
    }
}
