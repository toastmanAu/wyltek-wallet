package com.wyltek.wallet.prepaid

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RpcChainPortTest {
    @Test fun match_is_case_insensitive_true() {
        assertTrue(broadcastOutcome("0xABcd", "0xabCD"))
    }
    @Test fun null_throws_unknown() {
        try { broadcastOutcome(null, "0xab"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("unknown")) }
    }
    @Test fun mismatch_throws_unknown() {
        try { broadcastOutcome("0x01", "0x02"); fail() } catch (e: IOException) { }
    }
}
