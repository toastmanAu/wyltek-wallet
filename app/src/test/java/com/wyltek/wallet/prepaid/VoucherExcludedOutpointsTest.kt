package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Final review LOW-4 (second half): a live voucher cell is never a fee input, even if its data were read back empty. */
class VoucherExcludedOutpointsTest {
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm("COF01", "Flat white", 450, "AUD", 1)

    @Test fun live_voucher_outpoints_are_excluded_and_closed_or_void_ones_are_not() = runTest {
        val h = VoucherHarness()
        assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val v = h.vstore.voucherByUid(uid)!!
        assertTrue(normalizeOutpoint(v.outpoint) in h.core.excludedOutpoints())
        h.vstore.updateVoucher(v.copy(state = VOUCHER_USED, countLeft = 0))
        assertTrue(normalizeOutpoint(v.outpoint) in h.core.excludedOutpoints())
        h.vstore.updateVoucher(v.copy(state = VOUCHER_CLOSED, countLeft = 0))
        assertFalse(normalizeOutpoint(v.outpoint) in h.core.excludedOutpoints())
        h.vstore.updateVoucher(v.copy(state = VOUCHER_VOID, countLeft = 0))
        assertFalse(normalizeOutpoint(v.outpoint) in h.core.excludedOutpoints())
    }

    @Test fun a_voucher_cell_that_looks_pure_is_still_never_a_fee_input() = runTest {
        val h = VoucherHarness(walletCkb = 200uL)
        assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val v = h.vstore.voucherByUid(uid)!!
        h.chain.cells[v.outpoint] = h.chain.cells[v.outpoint]!!.copy(data = null)      // as if the RPC returned no data
        val lock = h.core.lock
        // 100 CKB out of a ~131 CKB change cell leaves less than a 61 CKB change cell: only the voucher cell would make it fit.
        // Control: without the exclusion the planner WOULD pick the voucher cell (so this test can fail).
        val unguarded = PrepaidTxPlanner.plan(emptyList(), h.chain.cellsByLock(lock), emptySet(), listOf(PrepaidTxPlanner.output(100uL * 100_000_000uL, lock)), lock)
        assertTrue(unguarded.inputs.any { PrepaidTxPlanner.outpointKey(it) == normalizeOutpoint(v.outpoint) })
        try {
            val p = PrepaidTxPlanner.plan(emptyList(), h.chain.cellsByLock(lock), h.core.excludedOutpoints(), listOf(PrepaidTxPlanner.output(100uL * 100_000_000uL, lock)), lock)
            assertFalse(p.inputs.any { PrepaidTxPlanner.outpointKey(it) == normalizeOutpoint(v.outpoint) })
        } catch (e: InsufficientFunds) { /* refused rather than spending the voucher cell: correct */ }
    }
}
