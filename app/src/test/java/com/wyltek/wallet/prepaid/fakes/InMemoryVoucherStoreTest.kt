package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InMemoryVoucherStoreTest {
    private fun v(id: String, uid: String, at: Long = 1) = VoucherEntity(id, uid, "P", "Pn", "h", 100, "AUD", 5, 5, "0xaa:0", VOUCHER_ACTIVE, issuedAt = at, updatedAt = at)

    @Test fun uid_is_normalised_to_lowercase_and_compared_exactly() = runBlocking {
        val s = InMemoryVoucherStore()
        s.insertVoucher(v("a", "04AABB"))
        assertEquals("04aabb", s.vouchers["a"]!!.uid)
        assertNotNull(s.voucherByUid("04aabb")); assertNotNull(s.voucherByUid("04AABB"))
        s.vouchers["a"] = v("a", "04AABB") // bypass the boundary: a stored non-normalised uid must NOT match (exact compare)
        assertNull(s.voucherByUid("04aabb"))
    }

    @Test fun update_and_applyRedeems_fail_on_missing_row_without_side_effects() = runBlocking {
        val s = InMemoryVoucherStore()
        try { s.updateVoucher(v("x", "01")); fail() } catch (e: IllegalStateException) { assertEquals("voucher row missing", e.message) }
        val seen = JournalSeenEntity("h1", KIND_VOUCHER_REDEEM, "x", 1, 1, 1)
        try { s.applyRedeems(listOf(v("x", "01")), listOf(seen)); fail() } catch (e: IllegalStateException) {}
        assertTrue(s.seen.isEmpty())
    }

    @Test fun allVouchers_newest_update_first() = runBlocking {
        val s = InMemoryVoucherStore(); s.insertVoucher(v("a", "01", 1)); s.insertVoucher(v("b", "02", 2))
        assertEquals(listOf("b", "a"), s.allVouchers().map { it.voucherId })
    }
}
