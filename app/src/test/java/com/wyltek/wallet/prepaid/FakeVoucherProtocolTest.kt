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
    @Test fun records_the_native_decoder_refuses_are_bad_version() {
        val good = p.voucherSeal("", uid, rec)
        val reserved = good.copyOf().also { it[44] = 1 }
        val lower = good.copyOf().also { it[36] = 'a'.code.toByte() }
        for (bad in listOf(reserved, lower)) {
            val r = p.voucherSelectSlot("", uid, bad + ByteArray(64))
            assertEquals(SlotPickFfi.NONE, r.pick); assertEquals(com.wyltek.wallet.core.native.SlotReason.BAD_VERSION, r.reason)
        }
    }
    @Test fun normalisation_matches_rust_ascii_rules() {
        assertThrows(PrepaidException::class.java) { p.normaliseProductCode("\u000Bcof") }
        assertEquals("COF", p.normaliseProductCode("\u000Ccof\r\n"))
        assertEquals("CAFé", p.normaliseProductCode("café"))
        assertEquals("ß", p.normaliseProductCode("ß"))
    }
    @Test fun seal_refuses_malformed_input_as_invalid_input() {
        assertThrows(PrepaidException::class.java) { p.voucherSeal("", uid, rec.copy(currency = "aud")) }
        assertThrows(PrepaidException::class.java) { p.voucherSeal("", uid, rec.copy(voucherIdHex = "zz")) }
    }
}
