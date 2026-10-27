package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StaffLockoutSettleTest {
    private val key = "71".repeat(8)
    private val hash = "ab".repeat(32)
    private fun entry(kind: Int) = "02" + "%02x".format(kind) + "00".repeat(94)
    private fun TestScope.vh() = VoucherHarness(1000uL, scope = this)
    private fun lockout() = LockoutFfi(0u, 9.toUShort(), "04010203040506", key, 7.toUShort(), 3, hash)
    private fun result(vararg v: JournalVerdict) = BatchResultFfi(v.toList(), emptyList(), emptyList(), emptyList(), listOf(lockout()))
    private suspend fun enrol(h: VoucherHarness, revokedAt: Long? = null) =
        h.vstore.insertStaffKey(StaffKeyEntity(key, "04aabbccddeeff", "5a".repeat(32), "ring", 1, revokedAt))

    @Test fun a_lockout_is_accepted_and_writes_one_staff_lockout_row_with_terminal_and_pin_ver() = runTest {
        val h = vh(); enrol(h)
        h.protocol.verifyResult = result(JournalVerdict.ACCEPTED)
        assertEquals(listOf("accepted"), h.core.settle(entry(5), "t")!!.verdicts)
        val e = h.store.events.single { it.kind == KIND_STAFF_LOCKOUT }
        assertEquals(key, e.reserveId); assertEquals("04010203040506", e.uid)
        assertEquals(3L, e.amountUnits); assertEquals(9, e.balanceAfter); assertEquals(7L, e.counterAfter)
        assertTrue(e.detail, e.detail.startsWith("terminal 9; pin_ver 7; 3 wrong PINs; staff key $key (enrolled)"))
        assertEquals(KIND_STAFF_LOCKOUT, h.vstore.seen(hash)!!.kind)
    }

    @Test fun a_resent_lockout_answers_duplicate_with_no_second_row() = runTest {
        val h = vh()
        h.protocol.verifyResult = result(JournalVerdict.ACCEPTED)
        assertEquals(listOf("accepted"), h.core.settle(entry(5), "t")!!.verdicts)
        h.protocol.verifyResult = result(JournalVerdict.ACCEPTED)
        assertEquals(listOf("duplicate"), h.core.settle(entry(5), "t")!!.verdicts)
        assertEquals(1, h.store.events.count { it.kind == KIND_STAFF_LOCKOUT })
    }

    @Test fun a_lockout_for_an_unknown_or_revoked_key_is_still_accepted_and_says_so() = runTest {
        val h = vh()
        h.protocol.verifyResult = result(JournalVerdict.ACCEPTED)
        assertEquals(listOf("accepted"), h.core.settle(entry(5), "t")!!.verdicts)
        assertTrue(h.store.events.single { it.kind == KIND_STAFF_LOCKOUT }.detail.contains("(unknown)"))
        val h2 = vh(); enrol(h2, revokedAt = 5)
        h2.protocol.verifyResult = result(JournalVerdict.ACCEPTED)
        assertEquals(listOf("accepted"), h2.core.settle(entry(5), "t")!!.verdicts)
        assertTrue(h2.store.events.single { it.kind == KIND_STAFF_LOCKOUT }.detail.contains("(revoked)"))
    }

    /** Code review F3: the audit row is a lockout's only effect. If its write fails the terminal gets retry and resends;
     *  the resend must record the row, not answer duplicate off a seen marker written before the failure. */
    @Test fun a_lockout_whose_audit_write_fails_once_is_recorded_on_resend() = runTest {
        val h = vh(); enrol(h)
        h.store.failNextEvent = true
        h.protocol.verifyResult = result(JournalVerdict.ACCEPTED)
        assertEquals(listOf("retry"), h.core.settle(entry(5), "t")!!.verdicts)
        assertNull(h.vstore.seen(hash)); assertEquals(0, h.store.events.count { it.kind == KIND_STAFF_LOCKOUT })
        h.protocol.verifyResult = result(JournalVerdict.ACCEPTED)
        assertEquals(listOf("accepted"), h.core.settle(entry(5), "t")!!.verdicts)
        assertEquals(1, h.store.events.count { it.kind == KIND_STAFF_LOCKOUT }); assertEquals(KIND_STAFF_LOCKOUT, h.vstore.seen(hash)!!.kind)
    }

    @Test fun a_lockout_whose_verdict_was_not_accepted_is_left_alone() = runTest {
        val h = vh()
        h.protocol.verifyResult = result(JournalVerdict.BAD_MAC)
        assertEquals(listOf("bad_mac"), h.core.settle(entry(5), "t")!!.verdicts)
        assertEquals(0, h.store.events.count { it.kind == KIND_STAFF_LOCKOUT }); assertNull(h.vstore.seen(hash))
    }
}
