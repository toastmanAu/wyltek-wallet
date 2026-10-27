package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*

/** pin_ver is a u16 on the wire and 0 is reserved for "no PIN set". */
internal fun pinVerInRange(pinVer: Int): Boolean = pinVer in 1..65535

class NativeProtocol : Protocol {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = s.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun selectSlot(kHex: String, uidHex: String, raw128: ByteArray) = prepaidSelectSlot(kHex, uidHex, hex(raw128))
    override fun seal(kHex: String, uidHex: String, rec: CardRecordInfo) = unhex(prepaidSealRecord(kHex, uidHex, rec))
    override fun nextSlot(pick: SlotPickFfi) = prepaidNextSlot(pick)
    override fun reserveId(txHashHex: String, index: UInt) = prepaidReserveId(txHashHex, index)
    override fun merchantId(lock: LockScript) = prepaidMerchantId(lock.codeHash, lock.hashType, lock.args)
    override fun plan(action: PrepaidAction, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int) = prepaidPlan(action, balanceUnits, capacityShannons, amountUnits)
    override fun verifyBatch(kHex: String, merchantIdHex: String, entriesHex: String, cards: List<CardStateFfi>, vouchers: List<VoucherStateFfi>) = prepaidVerifyBatch(kHex, merchantIdHex, entriesHex, cards, vouchers)
    override fun voucherSelectSlot(kHex: String, uidHex: String, raw128: ByteArray) = prepaidVoucherSelectSlot(kHex, uidHex, hex(raw128))
    override fun voucherSeal(kHex: String, uidHex: String, rec: VoucherRecordInfo) = unhex(prepaidVoucherSeal(kHex, uidHex, rec))
    override fun normaliseProductCode(code: String) = prepaidNormaliseProductCode(code)
    override fun productHash(code: String) = prepaidProductHash(code)
    override fun voucherId(txHashHex: String, index: UInt) = prepaidVoucherId(txHashHex, index)
    override fun staffKeyId(uidHex: String, sigHex: String) = prepaidStaffKeyId(uidHex, sigHex)
    override fun staffPinCheck(kHex: String, keyIdHex: String, pinVer: Int, pin: String): String {
        require(pinVerInRange(pinVer)) { "pin_ver out of range" }
        return prepaidStaffPinCheck(kHex, keyIdHex, pinVer.toUShort(), pin)
    }
    /** Null for hash types rust-core's build_transaction cannot serialise (it writes every non-"type" as 0) (I8). */
    override fun lockOf(address: String): LockScript? = try {
        val i = decodeAddress(address)
        if (isSupportedHashType(i.lockHashType)) LockScript(i.lockCodeHash, i.lockHashType, i.lockArgs) else null
    } catch (e: Exception) { null }
}
