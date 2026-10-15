package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class VoucherSettleTest {
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm("COF01", "Flat white", 450, "AUD", 1)
    /** A 96-byte entry of [kind] (only byte 1 matters to Kotlin; the fake verifier decides the verdicts). */
    private fun entry(kind: Int) = "02" + "%02x".format(kind) + "00".repeat(94)
    /** The post-settlement close runs on the test scheduler: only when the test yields or advances. */
    private fun TestScope.vh(walletCkb: ULong = 1000uL) = VoucherHarness(walletCkb, scope = this)

    @Test fun accepted_redeem_counts_down_records_the_entry_and_closes_the_used_voucher() = runTest {
        val h = vh(); assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val v = h.vstore.voucherByUid(uid)!!
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(),
            listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        val r = h.core.settle(entry(3), "t")!!
        assertEquals(listOf("accepted"), r.verdicts)
        assertEquals(listOf(VoucherStateFfi(v.voucherId, 1u, false, emptyList())), h.protocol.lastVouchers)
        val settled = h.vstore.voucher(v.voucherId)!!
        assertEquals(0, settled.countLeft); assertEquals(1L, settled.tagCounter); assertEquals(VOUCHER_USED, settled.state)   // close is deferred
        advanceUntilIdle()
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state)
        assertNull(h.chain.cellAt(v.outpoint))
        assertEquals("ee".repeat(32), h.vstore.seenForVoucher(v.voucherId).single().entryHash)
        assertTrue(h.store.events.any { it.kind == KIND_VOUCHER_REDEEM && it.phase == PHASE_DONE })
    }

    @Test fun settled_entries_are_handed_back_to_the_verifier() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 3))
        val v = h.vstore.voucherByUid(uid)!!
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        h.core.settle(entry(3), "t")
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.DUPLICATE), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("duplicate"), h.core.settle(entry(3), "t")!!.verdicts)
        assertEquals(listOf(SettledRedeemFfi(1u, "ee".repeat(32))), h.protocol.lastVouchers.single().settled)
        assertEquals(2, h.vstore.voucher(v.voucherId)!!.countLeft)
    }

    @Test fun an_override_is_audited_once_and_a_resend_is_duplicate() = runTest {
        val h = vh()
        val o = OverrideFfi(0u, 4u, "5a".repeat(8), 500, 0, "AUD", "77".repeat(8), "dd".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), emptyList(), listOf(o))
        assertEquals(listOf("accepted"), h.core.settle(entry(4), "t")!!.verdicts)
        assertEquals(listOf("duplicate"), h.core.settle(entry(4), "t")!!.verdicts)
        assertEquals(1, h.store.events.count { it.kind == KIND_PRICE_OVERRIDE })
    }

    @Test fun new_verdicts_have_wire_names_and_unsupported_kinds_are_retried() = runTest {
        val h = vh()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.DOUBLE_REDEEM, JournalVerdict.UNKNOWN_VOUCHER, JournalVerdict.VOUCHER_VOID, JournalVerdict.UNSUPPORTED_KIND),
            emptyList(), emptyList(), emptyList())
        assertEquals(listOf("double_redeem", "unknown_voucher", "voucher_void", "retry"), h.core.settle(entry(3) + entry(3) + entry(3) + entry(9), "t")!!.verdicts)
    }

    @Test fun a_failed_card_batch_retries_card_entries_only() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 2))
        val v = h.vstore.voucherByUid(uid)!!
        val cardEntry = cardEntry("ab".repeat(32))
        h.store.insertCard(PrepaidCardEntity("ab".repeat(32), "04ebbb02cd2a81", null, "0xff:0", "0xff:0", 1000, 0, 0, CARD_ACTIVE, issuedAt = 0, updatedAt = 0))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED),
            listOf(CardNetFfi("ab".repeat(32), 100, 1u, 1u)), listOf(RedeemFfi(1u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        val r = h.core.settle(cardEntry + entry(3), "t")!!                // card reserve cell "0xff:0" is missing → card entry retried
        assertEquals(listOf("retry", "accepted"), r.verdicts)
        assertEquals(1, h.vstore.voucher(v.voucherId)!!.countLeft)
    }

    /** A kind-1 card entry carrying [reserveId] at bytes 20..52, where settlement reads it (main spec §8.1). */
    private fun cardEntry(reserveId: String) = "0201" + "00".repeat(18) + reserveId + "00".repeat(44)

    @Test fun a_refused_card_transaction_retries_the_card_entry_and_keeps_the_redeem() = runTest {
        val h = vh(2000uL); h.svc.issue(FakeTag(uid), form.copy(count = 2))
        val v = h.vstore.voucherByUid(uid)!!
        assertEquals(ActionOutcome.Done, h.core.issue(FakeTag("04ebbb02cd2a81"), 300_000, null))
        val card = h.store.allCards().single()
        h.chain.refuseBroadcast = true
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED),
            listOf(CardNetFfi(card.reserveId, 1000, 1u, 1u)), listOf(RedeemFfi(1u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        assertEquals(listOf("retry", "accepted"), h.core.settle(cardEntry(card.reserveId) + entry(3), "t")!!.verdicts)
        assertEquals(1, h.vstore.voucher(v.voucherId)!!.countLeft)
        assertEquals(300_000, h.store.allCards().single().balanceUnits)
    }

    // ---------------------------------------------------------------- carried obligations (Review Focus 5 and earlier reviews)

    private fun redeemAudits(h: VoucherHarness) = h.store.events.count { it.kind == KIND_VOUCHER_REDEEM && it.phase == PHASE_DONE }

    @Test fun an_identical_redeem_twice_in_one_batch_counts_down_once() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 3))
        val v = h.vstore.voucherByUid(uid)!!
        // Even if the verifier reported both copies as accepted, Kotlin must not decrement twice.
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED), emptyList(),
            listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32)), RedeemFfi(1u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        assertEquals(listOf("accepted", "duplicate"), h.core.settle(entry(3) + entry(3), "t")!!.verdicts)
        assertEquals(2, h.vstore.voucher(v.voucherId)!!.countLeft)
        assertEquals(1, redeemAudits(h))
    }

    @Test fun a_redeem_resent_in_a_later_batch_counts_down_once_and_audits_once() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 3))
        val v = h.vstore.voucherByUid(uid)!!
        val redeem = RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(redeem), emptyList())
        assertEquals(listOf("accepted"), h.core.settle(entry(3), "t")!!.verdicts)
        // Defence in depth: the verifier answers duplicate from the settled list; even an `accepted` with the same hash is a duplicate.
        assertEquals(listOf("duplicate"), h.core.settle(entry(3), "t")!!.verdicts)
        assertEquals(2, h.vstore.voucher(v.voucherId)!!.countLeft)
        assertEquals(1, redeemAudits(h))
    }

    @Test fun an_identical_override_twice_in_one_batch_is_audited_once() = runTest {
        val h = vh()
        val o = OverrideFfi(0u, 4u, "5a".repeat(8), 500, 0, "AUD", "77".repeat(8), "dd".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED), emptyList(), emptyList(), listOf(o, o.copy(index = 1u)))
        assertEquals(listOf("accepted", "duplicate"), h.core.settle(entry(4) + entry(4), "t")!!.verdicts)
        assertEquals(1, h.store.events.count { it.kind == KIND_PRICE_OVERRIDE })
        assertNotNull(h.vstore.seen("dd".repeat(32)))
    }

    @Test fun used_and_closed_vouchers_are_handed_to_the_verifier_and_only_void_ones_as_void() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form)
        val closed = h.vstore.voucherByUid(uid)!!
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, closed.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        h.core.settle(entry(3), "t"); advanceUntilIdle()
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(closed.voucherId)!!.state)
        h.svc.issue(FakeTag("04aaaaaaaaaaaa"), form)
        val voided = h.vstore.voucherByUid("04aaaaaaaaaaaa")!!
        assertEquals(ActionOutcome.Done, h.svc.void(voided, null))
        h.svc.issue(FakeTag("04bbbbbbbbbbbb"), form.copy(count = 2))
        val active = h.vstore.voucherByUid("04bbbbbbbbbbbb")!!
        // An identical resend of the closed voucher's last-use redeem: the verifier must still see its settled entry.
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.DUPLICATE), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("duplicate"), h.core.settle(entry(3), "t")!!.verdicts)
        val states = h.protocol.lastVouchers.associateBy { it.voucherIdHex }
        assertEquals(VoucherStateFfi(closed.voucherId, 0u, false, listOf(SettledRedeemFfi(1u, "ee".repeat(32)))), states[closed.voucherId])
        assertEquals(VoucherStateFfi(voided.voucherId, 0u, true, emptyList()), states[voided.voucherId])
        assertEquals(VoucherStateFfi(active.voucherId, 2u, false, emptyList()), states[active.voucherId])
        assertEquals(3, states.size)
    }

    /** A store whose writes fail, to prove nothing is answered `accepted` without its record. */
    private class FailingWrites(private val inner: InMemoryVoucherStore) : VoucherStore by inner {
        override suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) = throw IllegalStateException("disk full")
        override suspend fun insertSeen(e: JournalSeenEntity) = throw IllegalStateException("disk full")
    }

    @Test fun a_redeem_whose_record_cannot_be_written_is_retried_not_accepted() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 2))
        val v = h.vstore.voucherByUid(uid)!!
        VoucherService(h.core, FailingWrites(h.vstore))                    // installs itself as the hooks
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        assertEquals(listOf("retry"), h.core.settle(entry(3), "t")!!.verdicts)
        assertEquals(2, h.vstore.voucher(v.voucherId)!!.countLeft)
        assertEquals(0, redeemAudits(h))
    }

    @Test fun an_override_whose_record_cannot_be_written_is_retried_not_accepted() = runTest {
        val h = vh()
        VoucherService(h.core, FailingWrites(h.vstore))
        val o = OverrideFfi(0u, 4u, "5a".repeat(8), 500, 0, "AUD", "77".repeat(8), "dd".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), emptyList(), listOf(o))
        assertEquals(listOf("retry"), h.core.settle(entry(4), "t")!!.verdicts)
        assertEquals(0, h.store.events.count { it.kind == KIND_PRICE_OVERRIDE })
    }

    @Test fun a_redeem_of_a_voucher_missing_from_the_index_is_retried() = runTest {
        val h = vh()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, "99".repeat(32), 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        assertEquals(listOf("retry"), h.core.settle(entry(3), "t")!!.verdicts)
        assertNull(h.vstore.seen("ee".repeat(32)))
    }

    @Test fun a_throwing_after_settle_never_changes_the_verdicts() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form)
        val v = h.vstore.voucherByUid(uid)!!
        h.core.voucherHooks = object : VoucherHooks by h.svc { override suspend fun afterSettle() = throw IllegalStateException("boom") }
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        assertEquals(listOf("accepted"), h.core.settle(entry(3), "t")!!.verdicts)
        advanceUntilIdle()
        assertEquals(VOUCHER_USED, h.vstore.voucher(v.voucherId)!!.state)
    }

    @Test fun a_failed_close_leaves_the_voucher_used_and_the_verdict_accepted() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form)
        val v = h.vstore.voucherByUid(uid)!!
        h.chain.cellAtThrowsFor += v.outpoint
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        assertEquals(listOf("accepted"), h.core.settle(entry(3), "t")!!.verdicts)
        advanceUntilIdle()
        val after = h.vstore.voucher(v.voucherId)!!
        assertEquals(VOUCHER_USED, after.state); assertEquals(0, after.countLeft)
    }

    // ---------------------------------------------------------------- fix round 1: deferred close, accepted-only, per-entry tx hash

    @Test fun settle_returns_before_the_close_and_a_never_confirming_close_does_not_hold_it() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form)
        val v = h.vstore.voucherByUid(uid)!!
        h.chain.confirmAfterPolls = null                                  // the close transaction never confirms
        val before = h.chain.broadcasts.size
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        val start = testScheduler.currentTime
        assertEquals(listOf("accepted"), h.core.settle(entry(3), "t")!!.verdicts)
        assertEquals(start, testScheduler.currentTime)                    // no virtual time spent polling inside settle
        assertEquals(before, h.chain.broadcasts.size)                     // the close had not even started
        assertNull(h.vstore.voucher(v.voucherId)!!.pendingTxHash)
        advanceUntilIdle()                                                // the deferred close now runs (and times out)
        assertEquals(before + 1, h.chain.broadcasts.size)
        val after = h.vstore.voucher(v.voucherId)!!
        assertEquals(KIND_VOUCHER_CLOSE, after.pendingKind); assertEquals(VOUCHER_USED, after.state)
    }

    @Test fun a_busy_lock_skips_the_deferred_close_without_error_and_a_later_close_finishes_it() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form)
        val v = h.vstore.voucherByUid(uid)!!
        val before = h.chain.broadcasts.size
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
        assertEquals(listOf("accepted"), h.core.settle(entry(3), "t")!!.verdicts)
        assertNotNull(h.core.exclusive { yield() })                       // hold the lock while the queued close runs: it finds it busy
        advanceUntilIdle()
        assertEquals(before, h.chain.broadcasts.size)
        assertEquals(VOUCHER_USED, h.vstore.voucher(v.voucherId)!!.state)
        assertNull(h.vstore.voucher(v.voucherId)!!.pendingTxHash)
        assertEquals(1, h.svc.closeUsed())                                // the next opportunity closes it
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state)
    }

    @Test fun redeems_and_overrides_whose_verdict_is_not_accepted_are_not_acted_on() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 2))
        val v = h.vstore.voucherByUid(uid)!!
        val o = OverrideFfi(1u, 4u, "5a".repeat(8), 500, 0, "AUD", "77".repeat(8), "dd".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.DOUBLE_REDEEM, JournalVerdict.BAD_MAC), emptyList(),
            listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), listOf(o))
        assertEquals(listOf("double_redeem", "bad_mac"), h.core.settle(entry(3) + entry(4), "t")!!.verdicts)
        assertEquals(2, h.vstore.voucher(v.voucherId)!!.countLeft)
        assertNull(h.vstore.seen("ee".repeat(32))); assertNull(h.vstore.seen("dd".repeat(32)))
        assertEquals(0, redeemAudits(h)); assertEquals(0, h.store.events.count { it.kind == KIND_PRICE_OVERRIDE })
    }

    @Test fun the_batch_tx_hash_is_reported_for_card_entries_only() = runTest {
        val h = vh(2000uL); h.svc.issue(FakeTag(uid), form.copy(count = 2))
        val v = h.vstore.voucherByUid(uid)!!
        assertEquals(ActionOutcome.Done, h.core.issue(FakeTag("04ebbb02cd2a81"), 300_000, null))
        val card = h.store.allCards().single()
        val o = OverrideFfi(2u, 4u, "5a".repeat(8), 500, 0, "AUD", "77".repeat(8), "dd".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED),
            listOf(CardNetFfi(card.reserveId, 1000, 1u, 1u)), listOf(RedeemFfi(1u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))), listOf(o))
        val r = h.core.settle(cardEntry(card.reserveId) + entry(3) + entry(4), "t")!!
        assertEquals(listOf("accepted", "accepted", "accepted"), r.verdicts)
        assertNotNull(r.txHash)
        assertEquals(listOf(r.txHash, null, null), r.verdicts.indices.map { r.txHashAt(it) })
        assertNull(PrepaidService.SettleResult(listOf("retry"), "0xab").txHashAt(0))
    }

    // ---------------------------------------------------------------- final review MEDIUM-2 / LOW-1

    @Test fun settlement_hands_this_vaults_merchant_id_to_the_verifier() = runTest {
        val h = vh()
        h.core.settle(entry(4), "t")
        assertEquals(h.core.merchantIdHex, h.protocol.lastMerchant)
    }

    private suspend fun enrol(h: VoucherHarness, keyId: String, revokedAt: Long? = null) =
        h.vstore.insertStaffKey(StaffKeyEntity(keyId, "04aabbccddeeff", "5a".repeat(32), "ring", 1, revokedAt))

    @Test fun redeem_audit_rows_record_the_staff_key_status_and_verdicts_are_unchanged() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 3))
        val v = h.vstore.voucherByUid(uid)!!
        enrol(h, "71".repeat(8)); enrol(h, "72".repeat(8), revokedAt = 5)
        h.protocol.verifyResult = BatchResultFfi(List(3) { JournalVerdict.ACCEPTED }, emptyList(), listOf(
            RedeemFfi(0u, 3u, v.voucherId, 1u, "71".repeat(8), "e1".repeat(32)),
            RedeemFfi(1u, 3u, v.voucherId, 2u, "72".repeat(8), "e2".repeat(32)),
            RedeemFfi(2u, 3u, v.voucherId, 3u, "73".repeat(8), "e3".repeat(32))), emptyList())
        assertEquals(List(3) { "accepted" }, h.core.settle(entry(3) + entry(3) + entry(3), "t")!!.verdicts)
        val details = h.store.events.filter { it.kind == KIND_VOUCHER_REDEEM && it.phase == PHASE_DONE }.map { it.detail }
        assertEquals(3, details.size)
        assertTrue(details[0], details[0].endsWith("staff key ${"71".repeat(8)} (enrolled)"))
        assertTrue(details[1], details[1].endsWith("staff key ${"72".repeat(8)} (revoked)"))
        assertTrue(details[2], details[2].endsWith("staff key ${"73".repeat(8)} (unknown)"))
    }

    @Test fun override_audit_rows_record_the_staff_key_status() = runTest {
        val h = vh(); enrol(h, "72".repeat(8), revokedAt = 5)
        val o = OverrideFfi(0u, 4u, "5a".repeat(8), 500, 0, "AUD", "72".repeat(8), "dd".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), emptyList(), listOf(o))
        assertEquals(listOf("accepted"), h.core.settle(entry(4), "t")!!.verdicts)
        val d = h.store.events.single { it.kind == KIND_PRICE_OVERRIDE }.detail
        assertTrue(d, d.contains("staff key ${"72".repeat(8)} (revoked)"))
    }
}
