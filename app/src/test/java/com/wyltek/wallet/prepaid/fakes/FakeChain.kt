package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.prepaid.ChainPort
import com.wyltek.wallet.prepaid.PrepaidTxPlanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// A tiny UTXO set that applies a broadcast tx the first time it reports CONFIRMED.
class FakeChain : ChainPort {
    val cells = linkedMapOf<String, Utxo>()                       // key "txhash:index"
    val broadcasts = mutableListOf<Pair<String, JsonObject>>()
    val statusQueue = mutableMapOf<String, ArrayDeque<TxStatus>>() // explicit per-hash script; last value repeats
    /** Without an explicit queue: PENDING for this many polls, then CONFIRMED. Null = PENDING forever. */
    var confirmAfterPolls: Int? = 1
    var refuseBroadcast = false
    var throwOnStatusOnce = false
    private val polls = mutableMapOf<String, Int>()
    private val applied = mutableSetOf<String>()

    fun add(u: Utxo) { cells[PrepaidTxPlanner.outpointKey(u)] = u }
    override suspend fun cellsByLock(lock: LockScript) = cells.values.filter { it.lock == lock }
    override suspend fun cellAt(outpoint: String) = cells[outpoint]
    override suspend fun broadcast(txJson: JsonObject, txHash: String): Boolean {
        if (refuseBroadcast) return false
        val missing = txJson["inputs"]!!.jsonArray.any { i ->
            val po = i.jsonObject["previous_output"]!!.jsonObject
            (po["tx_hash"]!!.jsonPrimitive.content + ":" + po["index"]!!.jsonPrimitive.content.removePrefix("0x").toUInt(16)) !in cells
        }
        if (missing) return false
        broadcasts += txHash to txJson
        return true
    }
    override suspend fun status(txHash: String): TxStatus? {
        if (throwOnStatusOnce) { throwOnStatusOnce = false; throw java.io.IOException("rpc down") }
        if (txHash !in statusQueue && broadcasts.none { it.first == txHash }) return null
        statusQueue[txHash]?.let { q ->
            val s = if (q.size > 1) q.removeFirst() else q.first()
            if (s == TxStatus.CONFIRMED) applyOnce(txHash)
            return s
        }
        val n = (polls[txHash] ?: 0) + 1; polls[txHash] = n
        val c = confirmAfterPolls ?: return TxStatus.PENDING
        return if (n >= c) { applyOnce(txHash); TxStatus.CONFIRMED } else TxStatus.PENDING
    }
    fun confirm(txHash: String) { statusQueue[txHash] = ArrayDeque(listOf(TxStatus.CONFIRMED)) }
    fun reject(txHash: String) { statusQueue[txHash] = ArrayDeque(listOf(TxStatus.REJECTED)) }
    private fun applyOnce(txHash: String) {
        if (!applied.add(txHash)) return
        val j = broadcasts.firstOrNull { it.first == txHash }?.second ?: return
        j["inputs"]!!.jsonArray.forEach { i ->
            val po = i.jsonObject["previous_output"]!!.jsonObject
            cells.remove(po["tx_hash"]!!.jsonPrimitive.content + ":" + po["index"]!!.jsonPrimitive.content.removePrefix("0x").toUInt(16))
        }
        j["outputs"]!!.jsonArray.forEachIndexed { idx, o ->
            val lo = o.jsonObject["lock"]!!.jsonObject
            add(Utxo(OutPoint(txHash, idx.toUInt()), o.jsonObject["capacity"]!!.jsonPrimitive.content.removePrefix("0x").toULong(16),
                LockScript(lo["code_hash"]!!.jsonPrimitive.content, lo["hash_type"]!!.jsonPrimitive.content, lo["args"]!!.jsonPrimitive.content)))
        }
    }
}
