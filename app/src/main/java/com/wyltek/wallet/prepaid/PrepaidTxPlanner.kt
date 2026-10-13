package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.TxOutput
import com.wyltek.wallet.data.CellSelection

/** The lock hash types rust-core's `build_transaction` serialises correctly: "type" → 1, everything else → 0,
 *  so only "type" and "data" round-trip; data1/data2 would sign a different transaction than the node sees. */
fun isSupportedHashType(hashType: String): Boolean = hashType == "type" || hashType == "data"

data class CellDepRef(val txHash: String, val index: UInt, val depType: String)

data class PlannedTx(val inputs: List<Utxo>, val outputs: List<TxOutput>, val feeShannons: ULong, val changeIndex: Int)

class InsufficientFunds(val shortfallShannons: ULong) :
    Exception("insufficient wallet funds: short by ${shortfallShannons / 100_000_000uL} CKB")

/** Input selection and output layout for every prepaid reserve transaction (D3 spec §2.3, §5).
 *  Inputs: the reserve cells first, then the smallest pure-CKB wallet cells until the fixed outputs,
 *  a size-based fee and a change output of at least [MIN_CHANGE_SHANNONS] are covered. The merchant
 *  change is always the LAST output, so settlement successors keep their planned indices. */
object PrepaidTxPlanner {
    /** Same floor `sendCkb` keeps for its change (`minCellCapacity`). */
    val MIN_CHANGE_SHANNONS: ULong = 81uL * 100_000_000uL

    /** Over-estimate at 1 shannon/byte: ~85 B secp witness + ~44 B per input + ~100 B per output. */
    fun fee(numInputs: Int, numOutputs: Int): ULong = 1_000uL + 300uL * (numInputs + numOutputs).toULong()

    fun output(capacity: ULong, lock: LockScript) =
        TxOutput(capacity, lock.codeHash, lock.hashType, lock.args, "", "", "", "")

    fun outpointKey(u: Utxo) = "${ensure0x(u.outPoint.txHash)}:${u.outPoint.index}"

    fun plan(reserveInputs: List<Utxo>, walletCells: List<Utxo>, excludeOutpoints: Set<String>, fixedOutputs: List<TxOutput>, merchantLock: LockScript): PlannedTx {
        val need = fixedOutputs.fold(0uL) { a, o -> a + o.capacity }
        val reserveKeys = reserveInputs.map { outpointKey(it) }.toSet()
        val excluded = excludeOutpoints.map { normalizeOutpoint(it) }.toSet()
        val candidates = walletCells
            .filter { CellSelection.isPureCkb(it.type_ != null, it.data) }
            .filter { outpointKey(it) !in excluded && outpointKey(it) !in reserveKeys }
            .sortedBy { it.capacity }
        val selected = reserveInputs.toMutableList()
        var total = selected.fold(0uL) { a, u -> a + u.capacity }
        fun required() = need + fee(selected.size, fixedOutputs.size + 1) + MIN_CHANGE_SHANNONS
        for (c in candidates) {
            if (total >= required()) break
            selected += c; total += c.capacity
        }
        if (total < required()) throw InsufficientFunds(required() - total)
        val f = fee(selected.size, fixedOutputs.size + 1)
        return PlannedTx(selected, fixedOutputs + output(total - need - f, merchantLock), f, fixedOutputs.size)
    }
}
