package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.PrepaidException
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.VoucherRecordInfo
import com.wyltek.wallet.prepaid.fakes.FakeProtocol
import org.junit.Assert.*
import org.junit.Test

class FakeVoucherProtocolTest {
    private val p = FakeProtocol(); private val uid = "04958caa5c5e80"
    private val rec = VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, "1122334455667788")
    @Test fun voucher_seal_select_round_trips_and_is_never_a_card() {
        val raw = p.voucherSeal("", uid, rec) + ByteArray(64)
        assertEquals(SlotPickFfi.A, p.voucherSelectSlot("", uid, raw).pick)
        assertEquals(rec, p.voucherSelectSlot("", uid, raw).record)
        assertNull(p.selectSlot("", uid, raw).record)
        assertNull(p.voucherSelectSlot("", uid, p.seal("", uid, com.wyltek.wallet.core.native.CardRecordInfo(0u, "a0".repeat(32), 1, 0u, "1122334455667788")) + ByteArray(64)).record)
    }
    @Test fun product_codes_normalise_and_refuse() {
        assertEquals("COF01", p.normaliseProductCode("  cof01 "))
        assertEquals(p.productHash("cof01"), p.productHash(" COF01"))
        assertThrows(PrepaidException::class.java) { p.normaliseProductCode("   ") }
        assertThrows(PrepaidException::class.java) { p.normaliseProductCode("a".repeat(32)) }
    }
}
