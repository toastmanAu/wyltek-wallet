package com.wyltek.wallet.core.chain

import com.wyltek.wallet.core.model.LockScript
import org.junit.Assert.assertEquals
import org.junit.Test

class LightClientProviderTest {
    @Test fun builds_one_script_status_per_lock_at_start_block() {
        val locks = listOf(
            LockScript(codeHash = "0xaa", hashType = "type", args = "0x01"),
            LockScript(codeHash = "0xbb", hashType = "type", args = "0x02"),
        )
        val statuses = LightClientProvider.buildScriptStatuses(locks, "0x148335b")
        assertEquals(2, statuses.size)
        assertEquals("lock", statuses[0].scriptType)
        assertEquals("0x148335b", statuses[0].blockNumber)
        assertEquals("0xaa", statuses[0].script.code_hash)
        assertEquals("0xbb", statuses[1].script.code_hash)
    }
}
