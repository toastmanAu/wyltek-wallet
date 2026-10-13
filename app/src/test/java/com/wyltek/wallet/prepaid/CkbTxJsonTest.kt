package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CkbTxJsonTest {
    @Test fun `shape matches the node's send_transaction expectations`() {
        val lock = LockScript("0x" + "11".repeat(32), "type", "0xabcd")
        val inputs = listOf(Utxo(OutPoint("0x" + "aa".repeat(32), 1u), 100uL, lock), Utxo(OutPoint("0x" + "bb".repeat(32), 0u), 200uL, lock))
        val outputs = listOf(PrepaidTxPlanner.output(255uL, lock))
        val j = CkbTxJson.build(inputs, outputs, CellDepRef("0x" + "cc".repeat(32), 0u, "dep_group"), "0x55")
        assertEquals("0x0", j["version"]!!.jsonPrimitive.content)
        assertEquals("0x1", j["inputs"]!!.jsonArray[0].jsonObject["previous_output"]!!.jsonObject["index"]!!.jsonPrimitive.content)
        assertEquals("0xff", j["outputs"]!!.jsonArray[0].jsonObject["capacity"]!!.jsonPrimitive.content)
        assertEquals(null, j["outputs"]!!.jsonArray[0].jsonObject["type"])
        assertEquals("0x", j["outputs_data"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(listOf("0x55", "0x"), j["witnesses"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("dep_group", j["cell_deps"]!!.jsonArray[0].jsonObject["dep_type"]!!.jsonPrimitive.content)
        assertEquals(0, j["header_deps"]!!.jsonArray.size)
    }
}
