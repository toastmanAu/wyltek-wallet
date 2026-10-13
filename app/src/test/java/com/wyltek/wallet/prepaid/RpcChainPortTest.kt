package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.RpcException
import com.wyltek.wallet.core.chain.TxStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RpcChainPortTest {
    private val bare = "ab".repeat(32)

    @Test fun match_is_case_insensitive_true() {
        assertTrue(broadcastOutcome("0xABcd", "0xabCD"))
    }
    @Test fun built_hash_with_0x_matches_node_echo_with_0x() {
        assertTrue(broadcastOutcome("0x$bare", ensure0x(bare)))
    }
    @Test fun mixed_case_and_missing_prefix_still_match() {
        assertTrue(broadcastOutcome("0x" + bare.uppercase(), bare))
        assertTrue(broadcastOutcome("0X$bare", "0x" + bare.uppercase()))
    }
    @Test fun null_throws_unknown() {
        try { broadcastOutcome(null, "0xab"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("unknown")) }
    }
    @Test fun mismatch_throws_unknown() {
        try { broadcastOutcome("0x01", "0x02"); fail() } catch (e: IOException) { }
    }

    @Test fun status_maps_every_explicit_node_answer() = runTest {
        val answers = mapOf("pending" to TxStatus.PENDING, "proposed" to TxStatus.PENDING, "committed" to TxStatus.CONFIRMED,
            "rejected" to TxStatus.REJECTED, "unknown" to TxStatus.UNKNOWN)
        for ((raw, want) in answers) assertEquals(raw, want, RpcChainPort(ChainManager(), strictStatus = { raw }).status("0x$bare"))
    }
    @Test fun status_throws_on_rpc_error_instead_of_reporting_unknown() = runTest {
        val port = RpcChainPort(ChainManager(), strictStatus = { throw RpcException("Network error: timeout") })
        try { port.status("0x$bare"); fail("an outage must not read as UNKNOWN") } catch (e: RpcException) { }
    }
    @Test fun status_throws_on_an_unrecognised_answer() = runTest {
        try { RpcChainPort(ChainManager(), strictStatus = { "garbage" }).status("0x$bare"); fail() } catch (e: IOException) { }
    }
    @Test fun status_throws_when_no_rpc_provider_is_active() = runTest {
        try { RpcChainPort(ChainManager()).status("0x$bare"); fail() } catch (e: IOException) { }
    }
    @Test fun status_sends_a_0x_hash_even_for_a_bare_one() = runTest {
        var sent = ""
        RpcChainPort(ChainManager(), strictStatus = { sent = it; "pending" }).status(bare.uppercase())
        assertEquals("0x$bare", sent)
    }
}
