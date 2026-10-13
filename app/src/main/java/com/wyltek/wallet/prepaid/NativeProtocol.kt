package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*

class NativeProtocol : Protocol {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = s.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun selectSlot(kHex: String, uidHex: String, raw128: ByteArray) = prepaidSelectSlot(kHex, uidHex, hex(raw128))
    override fun seal(kHex: String, uidHex: String, rec: CardRecordInfo) = unhex(prepaidSealRecord(kHex, uidHex, rec))
    override fun nextSlot(pick: SlotPickFfi) = prepaidNextSlot(pick)
    override fun reserveId(txHashHex: String, index: UInt) = prepaidReserveId(txHashHex, index)
    override fun merchantId(lock: LockScript) = prepaidMerchantId(lock.codeHash, lock.hashType, lock.args)
    override fun plan(action: PrepaidAction, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int) = prepaidPlan(action, balanceUnits, capacityShannons, amountUnits)
    override fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>) = prepaidVerifyBatch(kHex, entriesHex, cards)
    /** Null for hash types rust-core's build_transaction cannot serialise (it writes every non-"type" as 0) (I8). */
    override fun lockOf(address: String): LockScript? = try {
        val i = decodeAddress(address)
        if (isSupportedHashType(i.lockHashType)) LockScript(i.lockCodeHash, i.lockHashType, i.lockArgs) else null
    } catch (e: Exception) { null }
}
