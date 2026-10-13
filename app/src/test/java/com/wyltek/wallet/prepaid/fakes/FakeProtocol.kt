package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.Protocol

// The record layout of demo-tier §4.1 with a fake 8-byte MAC (sha256 of uid||bytes[0..56]),
// so flows can be tested without the native library. Crypto itself is proven in rust-core.
class FakeProtocol : Protocol {
    val locks = mutableMapOf<String, LockScript>()    // address -> lock for lockOf
    var verifyResult: BatchResultFfi = BatchResultFfi(emptyList(), emptyList())
    private fun mac(uidHex: String, raw: ByteArray): ByteArray {
        val d = java.security.MessageDigest.getInstance("SHA-256"); d.update(uidHex.lowercase().toByteArray()); d.update(raw, 0, 56); return d.digest().copyOf(8)
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = s.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun decode(raw: ByteArray): CardRecordInfo? {
        if (String(raw, 0, 4, Charsets.US_ASCII) != "BBD1" || raw[4] != 0x02.toByte() || raw[5] != 0x01.toByte()) return null
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return CardRecordInfo(writerId = bb.getShort(6).toUShort(), reserveIdHex = hex(raw.copyOfRange(8, 40)), balanceUnits = bb.getInt(40),
            counter = bb.getInt(44).toUInt(), merchantIdHex = hex(raw.copyOfRange(48, 56)))
    }
    private fun valid(uidHex: String, raw: ByteArray) = decode(raw)?.takeIf { mac(uidHex, raw).contentEquals(raw.copyOfRange(56, 64)) }

    override fun selectSlot(kHex: String, uidHex: String, raw128: ByteArray): SlotResult {
        require(raw128.size == 128)
        val a = raw128.copyOfRange(0, 64); val b = raw128.copyOfRange(64, 128)
        val va = valid(uidHex, a); val vb = valid(uidHex, b)
        val none = SlotResult(SlotPickFfi.NONE, null, SlotReason.NO_VALID_SLOT, null)
        return when {
            va == null && vb == null -> {
                val badVer = listOf(a, b).any { String(it, 0, 4, Charsets.US_ASCII) == "BBD1" && decode(it) == null }
                if (badVer) none.copy(reason = SlotReason.BAD_VERSION)
                else listOf(a, b).firstNotNullOfOrNull { decode(it) }?.let { none.copy(reason = SlotReason.MAC_FAIL, merchantHintHex = it.merchantIdHex) } ?: none
            }
            va != null && vb == null -> SlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            va == null -> SlotResult(SlotPickFfi.B, vb, SlotReason.NO_VALID_SLOT, null)
            va!!.counter > vb!!.counter -> SlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            vb.counter > va.counter -> SlotResult(SlotPickFfi.B, vb, SlotReason.NO_VALID_SLOT, null)
            a.contentEquals(b) -> SlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            else -> SlotResult(SlotPickFfi.AMBIGUOUS, null, SlotReason.NO_VALID_SLOT, null)
        }
    }
    override fun seal(kHex: String, uidHex: String, rec: CardRecordInfo): ByteArray {
        val raw = ByteArray(64)
        "BBD1".toByteArray(Charsets.US_ASCII).copyInto(raw, 0); raw[4] = 0x02; raw[5] = 0x01
        raw[6] = rec.writerId.toByte(); raw[7] = (rec.writerId.toInt() shr 8).toByte()
        unhex(rec.reserveIdHex).copyInto(raw, 8); le32(rec.balanceUnits).copyInto(raw, 40); le32(rec.counter.toInt()).copyInto(raw, 44)
        unhex(rec.merchantIdHex).copyInto(raw, 48); mac(uidHex, raw).copyInto(raw, 56)
        return raw
    }
    override fun nextSlot(pick: SlotPickFfi) = if (pick == SlotPickFfi.A) SlotPickFfi.B else SlotPickFfi.A
    override fun reserveId(txHashHex: String, index: UInt): String {
        val d = java.security.MessageDigest.getInstance("SHA-256"); d.update((txHashHex + ":" + index).toByteArray()); return hex(d.digest())
    }
    override fun merchantId(lock: LockScript) = "1122334455667788"
    override fun plan(action: PrepaidAction, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int): ReservePlan {
        val unit = 100_000uL; val floor = 6_100_000_000uL; val max = 1_000_000
        fun expected(b: Int) = floor + b.toULong() * unit
        fun bad(m: String): Nothing = throw PrepaidException.Plan(m)
        return when (action) {
            PrepaidAction.ISSUE -> { if (amountUnits <= 0) bad("amount must be positive"); if (amountUnits > max) bad("over cap"); ReservePlan(expected(amountUnits), 0uL, amountUnits) }
            PrepaidAction.LOAD -> { if (amountUnits <= 0) bad("amount must be positive"); if (amountUnits > max) bad("over cap")
                if (capacityShannons != expected(balanceUnits)) bad("reserve capacity does not match the index balance; use Inspect")
                val after = balanceUnits.toLong() + amountUnits; if (after > Int.MAX_VALUE) bad("balance overflow"); ReservePlan(expected(after.toInt()), 0uL, after.toInt()) }
            PrepaidAction.VOID -> { if (capacityShannons < floor) bad("below floor"); ReservePlan(0uL, 0uL, 0) }
            PrepaidAction.CASH_OUT -> { if (balanceUnits <= 0) bad("amount must be positive"); if (capacityShannons != expected(balanceUnits)) bad("mismatch")
                val c = balanceUnits.toULong() * unit; if (c < floor) bad("balance below the 61 CKB minimum cell; pay out in cash and void"); ReservePlan(0uL, c, 0) }
        }
    }
    var lastCards: List<CardStateFfi> = emptyList()
    override fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>): BatchResultFfi { lastCards = cards; return verifyResult }
    override fun lockOf(address: String) = locks[address]
}
