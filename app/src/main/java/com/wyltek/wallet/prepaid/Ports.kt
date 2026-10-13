package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.*
import kotlinx.serialization.json.JsonObject

interface ChainPort {
    suspend fun cellsByLock(lock: LockScript): List<Utxo>
    /** `"txhash:index"` (decimal index). Null when spent or unknown. */
    suspend fun cellAt(outpoint: String): Utxo?
    /** [txHash] is the hash rust-core computed at build time; a real node returns the same hash.
     *  True when the node accepted the transaction, false when it refused. */
    suspend fun broadcast(txJson: JsonObject, txHash: String): Boolean
    suspend fun status(txHash: String): TxStatus?
}

interface SignerPort {
    val lock: LockScript
    val cellDep: CellDepRef
    fun build(inputs: List<Utxo>, outputs: List<TxOutput>): BuiltTransaction
    fun witness0(txHashHex: String, numInputs: Int): String
}

/** The rust-core protocol behind an interface so the service is host-testable. `NativeProtocol` is the real one. */
interface Protocol {
    fun selectSlot(kHex: String, uidHex: String, raw128: ByteArray): SlotResult
    fun seal(kHex: String, uidHex: String, rec: CardRecordInfo): ByteArray
    fun nextSlot(pick: SlotPickFfi): SlotPickFfi
    fun reserveId(txHashHex: String, index: UInt): String
    fun merchantId(lock: LockScript): String
    /** Throws PrepaidException on refusal. */
    fun plan(action: PrepaidAction, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int): ReservePlan
    fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>): BatchResultFfi
    fun lockOf(address: String): LockScript?
}

class TagLostException(message: String) : Exception(message)

interface TagPort {
    val uidHex: String
    fun isPresent(): Boolean
    /** Pages 4..35 as 128 bytes: slot A then slot B. */
    suspend fun readSlots(): ByteArray
    /** Writes the 16 pages of one slot in order, MAC pages (14, 15) last. Throws [TagLostException]. */
    suspend fun writeSlot(slot: SlotPickFfi, raw64: ByteArray)
    suspend fun zeroSlot(slot: SlotPickFfi)
}

enum class Phase { REQUESTED, BROADCAST, COMMITTED, WRITTEN, DONE, FAILED }

sealed class ActionOutcome {
    data object Done : ActionOutcome()
    data class AwaitingTag(val reserveId: String) : ActionOutcome()
    data class StillConfirming(val txHash: String) : ActionOutcome()
    data class Failed(val reason: String) : ActionOutcome()
}
