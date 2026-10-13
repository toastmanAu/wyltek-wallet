package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.TxOutput
import kotlinx.serialization.json.*

object CkbTxJson {
    fun build(inputs: List<Utxo>, outputs: List<TxOutput>, dep: CellDepRef, witness0: String): JsonObject = buildJsonObject {
        put("version", "0x0")
        put("cell_deps", buildJsonArray {
            add(buildJsonObject {
                put("out_point", buildJsonObject { put("tx_hash", dep.txHash); put("index", "0x${dep.index.toString(16)}") })
                put("dep_type", dep.depType)
            })
        })
        put("header_deps", buildJsonArray {})
        put("inputs", buildJsonArray {
            for (i in inputs) add(buildJsonObject {
                put("previous_output", buildJsonObject { put("tx_hash", i.outPoint.txHash); put("index", "0x${i.outPoint.index.toString(16)}") })
                put("since", "0x0")
            })
        })
        put("outputs", buildJsonArray {
            for (o in outputs) add(buildJsonObject {
                put("capacity", "0x${o.capacity.toString(16)}")
                put("lock", buildJsonObject { put("code_hash", o.lockCodeHash); put("hash_type", o.lockHashType); put("args", o.lockArgs) })
                if (o.typeCodeHash.isNotBlank()) put("type", buildJsonObject { put("code_hash", o.typeCodeHash); put("hash_type", o.typeHashType); put("args", o.typeArgs) })
            })
        })
        put("outputs_data", buildJsonArray { for (o in outputs) add(o.data.ifEmpty { "0x" }) })
        put("witnesses", buildJsonArray { add(witness0); for (i in 1 until inputs.size) add("0x") })
    }
}
