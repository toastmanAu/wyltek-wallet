package com.wyltek.wallet.core.chain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptStatusTest {
    @Test fun serializes_to_light_client_set_scripts_shape() {
        val s = ScriptStatus(
            script = Script(code_hash = "0xdead", hash_type = "type", args = "0xbeef"),
            blockNumber = "0x148335b"
        )
        val out = Json.encodeToString(ScriptStatus.serializer(), s)
        assertTrue(out.contains("\"script_type\":\"lock\""))
        assertTrue(out.contains("\"block_number\":\"0x148335b\""))
        assertTrue(out.contains("\"code_hash\":\"0xdead\""))
    }
}
