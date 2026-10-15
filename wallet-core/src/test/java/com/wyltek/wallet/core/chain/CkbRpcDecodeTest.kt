package com.wyltek.wallet.core.chain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The node's JSON field is `type`; the Kotlin property is `type_`. Without @SerialName every typed cell decoded as type-less. */
class CkbRpcDecodeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }   // CkbRpcClient's configuration

    // Shape of a real get_cells result (an sUDT cell and a plain cell), as returned by a CKB indexer.
    private val getCells = """
        {"last_cursor":"0x409bd7e06f","objects":[
          {"block_number":"0x14b6f3c","out_point":{"index":"0x0","tx_hash":"0x5f0f8c4c17e9e0e7c5b4c0e0c4f3b1a2d1e0f9e8d7c6b5a4f3e2d1c0b9a8f7e6"},
           "output":{"capacity":"0x34e62ce00","lock":{"args":"0xabababababababababababababababababababab","code_hash":"0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8","hash_type":"type"},
                     "type":{"args":"0x9ec9ae72e4579980e41554100f1219ff97599f8ab7e79c074b30f2fa241a790c","code_hash":"0xc5e5dcf215925f7ef4dfaf5f4b4f105bc321c02776d6e7d52a1db3fcd9d011a4","hash_type":"type"}},
           "output_data":"0x00e1f505000000000000000000000000","tx_index":"0x1"},
          {"block_number":"0x14b6f3d","out_point":{"index":"0x1","tx_hash":"0x6f0f8c4c17e9e0e7c5b4c0e0c4f3b1a2d1e0f9e8d7c6b5a4f3e2d1c0b9a8f7e6"},
           "output":{"capacity":"0x16b969d00","lock":{"args":"0xabababababababababababababababababababab","code_hash":"0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8","hash_type":"type"},"type":null},
           "output_data":"0x","tx_index":"0x0"}]}
    """.trimIndent()

    @Test fun get_cells_decodes_the_type_script() {
        val r = json.decodeFromString(CellsResponse.serializer(), getCells)
        val t = r.objects[0].output.type_!!
        assertEquals("0xc5e5dcf215925f7ef4dfaf5f4b4f105bc321c02776d6e7d52a1db3fcd9d011a4", t.code_hash)
        assertEquals("type", t.hash_type)
        assertEquals("0x9ec9ae72e4579980e41554100f1219ff97599f8ab7e79c074b30f2fa241a790c", t.args)
        assertNull(r.objects[1].output.type_)
    }

    @Test fun transaction_outputs_decode_the_type_script() {
        val tx = """{"version":"0x0","cell_deps":[],"header_deps":[],"inputs":[],"outputs":[
            {"capacity":"0x34e62ce00","lock":{"args":"0xab","code_hash":"0x9bd7","hash_type":"type"},"type":{"args":"0x01","code_hash":"0xc5e5","hash_type":"data1"}}],
            "outputs_data":["0x"],"witnesses":[]}"""
        val d = json.decodeFromString(TransactionDetailTx.serializer(), tx)
        assertEquals("data1", d.outputs.single().type_!!.hash_type)
    }

    @Test fun the_type_script_is_encoded_under_its_rpc_name() {
        val s = json.encodeToString(CellOutput.serializer(), CellOutput("0x1", Script("0x9bd7", "type", "0xab"), Script("0xc5e5", "type", "0x01")))
        assertEquals(true, s.contains("\"type\":{"))
    }
}
