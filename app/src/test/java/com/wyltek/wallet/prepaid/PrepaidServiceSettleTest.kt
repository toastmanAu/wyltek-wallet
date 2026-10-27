package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.FakeTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class PrepaidServiceSettleTest {
    private val ckb = 100_000_000uL; private val unit = 100_000uL; private val floor = 61uL * ckb
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private fun h() = PrepaidServiceIssueLoadTest.H(2000uL, lock)
    /** 96-byte entries with only the reserve_id (bytes 20..52) filled; FakeProtocol never parses them. */
    private fun entries(vararg rids: String) = rids.joinToString("") { rid -> "00".repeat(20) + rid + "00".repeat(44) }

    @Test fun positive_net_builds_one_tx_and_advances_counters() = runTest {
        val h = h()
        h.svc.issue(FakeTag("04000000000001"), 300_000, null); h.svc.issue(FakeTag("04000000000002"), 300_000, null)
        val (c1, c2) = h.store.allCards().sortedBy { it.uid }
        h.protocol.verifyResult = BatchResultFfi(
            verdicts = listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED, JournalVerdict.DUPLICATE, JournalVerdict.ACCEPTED),
            nets = listOf(CardNetFfi(c1.reserveId, 50_000, 7u, 2u), CardNetFfi(c2.reserveId, -10_000, 3u, 1u)), emptyList(), emptyList())
        val r = h.svc.settle(entries(c1.reserveId, c1.reserveId, c1.reserveId, c2.reserveId), "terminal 7")!!
        assertEquals(listOf("accepted", "accepted", "duplicate", "accepted"), r.verdicts)
        assertNotNull(r.txHash)
        val d1 = h.store.card(c1.reserveId)!!; val d2 = h.store.card(c2.reserveId)!!
        assertEquals(250_000, d1.balanceUnits); assertEquals(7L, d1.settledCounter); assertEquals(7L, d1.counter)
        assertEquals(310_000, d2.balanceUnits); assertEquals(3L, d2.settledCounter)
        assertEquals(floor + 250_000uL * unit, h.chain.cellAt(d1.currentOutpoint)!!.capacity)
        assertEquals(floor + 310_000uL * unit, h.chain.cellAt(d2.currentOutpoint)!!.capacity)
        assertEquals("${r.txHash}:0", d1.currentOutpoint); assertEquals("${r.txHash}:1", d2.currentOutpoint)
        val ev = h.store.events.filter { it.kind == KIND_SETTLE && it.phase == PHASE_DONE }
        assertEquals(2, ev.size); assertEquals(-50_000L, ev.first { it.reserveId == c1.reserveId }.amountUnits)
        assertTrue(ev.all { it.phase == PHASE_DONE && it.txHash == r.txHash && it.detail.contains("terminal 7") })
    }

    @Test fun zero_net_only_advances_counters_without_a_transaction() = runTest {
        val h = h(); h.svc.issue(FakeTag("04000000000001"), 300_000, null)
        val c1 = h.store.allCards().single()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED), listOf(CardNetFfi(c1.reserveId, 0, 9u, 2u)), emptyList(), emptyList())
        val r = h.svc.settle(entries(c1.reserveId, c1.reserveId), "t")!!
        assertNull(r.txHash); assertEquals(listOf("accepted", "accepted"), r.verdicts)
        assertEquals(9L, h.store.card(c1.reserveId)!!.settledCounter); assertEquals(1, h.chain.broadcasts.size)
    }

    @Test fun failed_transaction_turns_accepted_into_retry_and_advances_nothing() = runTest {
        val h = h(); h.svc.issue(FakeTag("04000000000001"), 300_000, null)
        val c1 = h.store.allCards().single()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.BAD_MAC), listOf(CardNetFfi(c1.reserveId, 1000, 2u, 1u)), emptyList(), emptyList())
        h.chain.refuseBroadcast = true
        val r = h.svc.settle(entries(c1.reserveId, c1.reserveId), "t")!!
        assertEquals(listOf("retry", "bad_mac"), r.verdicts); assertNull(r.txHash)
        assertEquals(0L, h.store.card(c1.reserveId)!!.settledCounter); assertEquals(300_000, h.store.card(c1.reserveId)!!.balanceUnits)
        assertEquals(PHASE_FAILED, h.store.events.last().phase); assertEquals(KIND_SETTLE, h.store.events.last().kind)
    }

    @Test fun closed_and_issuing_cards_are_unknown_to_settlement() = runTest {
        val h = h()
        h.svc.issue(FakeTag("04000000000001"), 300_000, null); val closed = h.store.allCards().single()
        h.svc.void(closed, null)
        h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag("04000000000002"), 300_000, null); val issuing = h.store.cardByUid("04000000000002")!!
        h.chain.confirmAfterPolls = 1
        h.svc.issue(FakeTag("04000000000003"), 300_000, null); val active = h.store.cardByUid("04000000000003")!!
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.UNKNOWN_CARD, JournalVerdict.UNKNOWN_CARD, JournalVerdict.ACCEPTED), listOf(CardNetFfi(active.reserveId, 0, 1u, 1u)), emptyList(), emptyList())
        val r = h.svc.settle(entries(closed.reserveId, issuing.reserveId, active.reserveId), "t")!!
        assertEquals(listOf(active.reserveId), h.protocol.lastCards.map { it.reserveIdHex })
        assertEquals(listOf("unknown_card", "retry", "accepted"), r.verdicts)   // a pending card's entries are retried, not refused
    }

    @Test fun active_card_with_a_pending_credit_is_not_eligible_and_its_entries_are_retried() = runTest {
        val h = h()
        val tag = FakeTag("04000000000001")
        h.svc.issue(tag, 300_000, null); val card = h.store.allCards().single()
        h.chain.confirmAfterPolls = null
        h.svc.load(tag, card, 100_000)                         // timeout: the card stays ACTIVE with a pending tx
        val pending = h.store.card(card.reserveId)!!
        assertNotNull(pending.pendingTxHash); assertEquals(CARD_ACTIVE, pending.state)
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.UNKNOWN_CARD), emptyList(), emptyList(), emptyList())
        val r = h.svc.settle(entries(card.reserveId), "t")!!
        assertEquals(emptyList<String>(), h.protocol.lastCards.map { it.reserveIdHex })
        assertEquals(listOf("retry"), r.verdicts)
    }

    private suspend fun twoCardTimeout(h: PrepaidServiceIssueLoadTest.H): Triple<PrepaidCardEntity, PrepaidCardEntity, String> {
        h.svc.issue(FakeTag("04000000000001"), 300_000, null); h.svc.issue(FakeTag("04000000000002"), 300_000, null)
        val (c1, c2) = h.store.allCards().sortedBy { it.uid }
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED),
            listOf(CardNetFfi(c1.reserveId, 50_000, 7u, 1u), CardNetFfi(c2.reserveId, -10_000, 3u, 1u)), emptyList(), emptyList())
        h.chain.confirmAfterPolls = null
        val r = h.svc.settle(entries(c1.reserveId, c2.reserveId), "t")!!
        assertEquals(listOf("retry", "retry"), r.verdicts); assertNull(r.txHash)
        val hash = h.chain.broadcasts.last().first
        val p1 = h.store.card(c1.reserveId)!!; val p2 = h.store.card(c2.reserveId)!!
        assertTrue(listOf(p1, p2).all { it.pendingKind == KIND_SETTLE && it.pendingTxHash == hash && !it.pendingCommitted })
        assertEquals(listOf(0, 1), listOf(p1.pendingUnits, p2.pendingUnits))
        assertEquals(listOf(250_000, 310_000), listOf(p1.pendingTagBalance, p2.pendingTagBalance))
        assertEquals(listOf(7L, 3L), listOf(p1.pendingTagCounter, p2.pendingTagCounter))
        assertEquals(300_000, p1.balanceUnits)
        return Triple(c1, c2, hash)
    }

    @Test fun settle_timeout_marks_moving_cards_and_recovery_applies_it() = runTest {
        val h = h(); val (c1, c2, hash) = twoCardTimeout(h)
        h.chain.confirm(hash)
        assertEquals(2, h.svc.recoverPending())
        val d1 = h.store.card(c1.reserveId)!!; val d2 = h.store.card(c2.reserveId)!!
        assertEquals(listOf(250_000, 310_000), listOf(d1.balanceUnits, d2.balanceUnits))
        assertEquals(listOf(7L, 3L), listOf(d1.settledCounter, d2.settledCounter))
        assertEquals(listOf("$hash:0", "$hash:1"), listOf(d1.currentOutpoint, d2.currentOutpoint))
        assertTrue(listOf(d1, d2).all { it.pendingTxHash == null && it.pendingKind == null && it.pendingUnits == null && it.pendingTagBalance == null })
        for (c in listOf(c1, c2)) assertEquals(PHASE_DONE, h.store.events.last { it.kind == KIND_SETTLE && it.reserveId == c.reserveId }.phase)
    }

    @Test fun settle_timeout_then_rejected_clears_marker_and_keeps_cards() = runTest {
        val h = h(); val (c1, c2, hash) = twoCardTimeout(h)
        h.chain.reject(hash)
        assertEquals(2, h.svc.recoverPending())
        for (c in listOf(c1, c2)) {
            val d = h.store.card(c.reserveId)!!
            assertEquals(300_000, d.balanceUnits); assertNull(d.pendingTxHash); assertNull(d.pendingKind); assertEquals(0L, d.settledCounter)
            assertEquals(PHASE_FAILED, h.store.events.last { it.kind == KIND_SETTLE && it.reserveId == c.reserveId }.phase)
        }
    }

    @Test fun settle_broadcast_refusal_is_audited() = runTest {
        val h = h(); h.svc.issue(FakeTag("04000000000001"), 300_000, null)
        val c1 = h.store.allCards().single()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), listOf(CardNetFfi(c1.reserveId, 1000, 1u, 1u)), emptyList(), emptyList())
        h.chain.refuseBroadcast = true
        h.svc.settle(entries(c1.reserveId), "t")
        val last = h.store.events.last { it.kind == KIND_SETTLE }
        assertEquals(PHASE_FAILED, last.phase); assertTrue(last.detail.isNotEmpty())
    }

    @Test fun bad_length_is_null() = runTest {
        val h = h()
        h.protocol.verifyResult = BatchResultFfi(emptyList(), emptyList(), emptyList(), emptyList())
        assertNull(h.svc.settle("00".repeat(95), "t"))
        assertNotNull(h.svc.settle("", "t"))
    }

    @Test fun settle_markers_are_persisted_before_broadcast_and_survive_process_death() = runTest {
        val h = h()
        h.svc.issue(FakeTag("04000000000001"), 300_000, null); h.svc.issue(FakeTag("04000000000002"), 300_000, null)
        val (c1, c2) = h.store.allCards().sortedBy { it.uid }
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED),
            listOf(CardNetFfi(c1.reserveId, 50_000, 7u, 1u), CardNetFfi(c2.reserveId, -10_000, 3u, 1u)), emptyList(), emptyList())
        h.chain.confirmAfterPolls = null
        val job = launch { h.svc.settle(entries(c1.reserveId, c2.reserveId), "t") }
        runCurrent(); job.cancelAndJoin()
        val hash = h.chain.broadcasts.last().first
        val p1 = h.store.card(c1.reserveId)!!; val p2 = h.store.card(c2.reserveId)!!
        assertTrue(listOf(p1, p2).all { it.pendingKind == KIND_SETTLE && it.pendingTxHash == hash })
        assertEquals(listOf(0, 1), listOf(p1.pendingUnits, p2.pendingUnits))
        assertEquals(listOf(250_000, 310_000), listOf(p1.pendingTagBalance, p2.pendingTagBalance))
        h.chain.confirm(hash)
        assertEquals(2, h.svc.recoverPending())
        val d1 = h.store.card(c1.reserveId)!!; val d2 = h.store.card(c2.reserveId)!!
        assertEquals(listOf(250_000, 310_000), listOf(d1.balanceUnits, d2.balanceUnits))
        assertEquals(listOf("$hash:0", "$hash:1"), listOf(d1.currentOutpoint, d2.currentOutpoint))
        assertTrue(listOf(d1, d2).all { it.pendingTxHash == null })
    }

    @Test fun refused_settlement_leaves_no_markers() = runTest {
        val h = h(); h.svc.issue(FakeTag("04000000000001"), 300_000, null)
        val c1 = h.store.allCards().single()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), listOf(CardNetFfi(c1.reserveId, 1000, 1u, 1u)), emptyList(), emptyList())
        h.chain.refuseBroadcast = true
        h.svc.settle(entries(c1.reserveId), "t")
        val d = h.store.card(c1.reserveId)!!
        assertNull(d.pendingTxHash); assertNull(d.pendingKind); assertNull(d.pendingUnits); assertEquals(300_000, d.balanceUnits)
    }

    @Test fun a_missing_reserve_retries_only_that_cards_entries() = runTest {
        val h = h()
        h.svc.issue(FakeTag("04000000000001"), 300_000, null); h.svc.issue(FakeTag("04000000000002"), 300_000, null)
        val (c1, c2) = h.store.allCards().sortedBy { it.uid }
        h.chain.cells.remove(c2.currentOutpoint)                                   // the indexer cannot see c2's reserve
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED, JournalVerdict.BAD_MAC),
            listOf(CardNetFfi(c1.reserveId, 50_000, 7u, 1u), CardNetFfi(c2.reserveId, 10_000, 3u, 1u)), emptyList(), emptyList())
        val r = h.svc.settle(entries(c1.reserveId, c2.reserveId, c2.reserveId), "t")!!
        assertEquals(listOf("accepted", "retry", "bad_mac"), r.verdicts)
        assertNotNull(r.txHash)
        val d1 = h.store.card(c1.reserveId)!!; val d2 = h.store.card(c2.reserveId)!!
        assertEquals(250_000, d1.balanceUnits); assertEquals("${r.txHash}:0", d1.currentOutpoint)
        assertEquals(300_000, d2.balanceUnits); assertEquals(0L, d2.settledCounter); assertEquals(c2.currentOutpoint, d2.currentOutpoint); assertNull(d2.pendingTxHash)
        assertTrue(h.store.events.any { it.kind == KIND_SETTLE && it.reserveId == c2.reserveId && it.phase == PHASE_FAILED })
    }

    @Test fun an_accepted_0x05_that_the_voucher_hook_fails_to_finalise_answers_retry() = runTest {
        val h = h()
        h.svc.voucherHooks = object : VoucherHooks by NoVoucherHooks {
            override suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String): List<String> = throw IllegalStateException("boom")
        }
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), emptyList(), emptyList())
        val r = h.svc.settle("00" + "05" + "00".repeat(94), "t")!!
        assertEquals(listOf("retry"), r.verdicts)
    }

    private fun voucherEntry(kind: Int) = "00" + "%02x".format(kind) + "00".repeat(94)
    private fun hookless(kind: Int, extraCard: Boolean = false) = runTest {
        val h = h()
        val i = if (extraCard) 1u else 0u
        val res = when (kind) {
            3 -> BatchResultFfi(emptyList(), emptyList(), listOf(RedeemFfi(i, 3u, "11".repeat(8), 1u, "77".repeat(8), "ee".repeat(32))), emptyList())
            4 -> BatchResultFfi(emptyList(), emptyList(), emptyList(), listOf(OverrideFfi(i, 4u, "5a".repeat(8), 500, 0, "AUD", "77".repeat(8), "dd".repeat(32))))
            else -> BatchResultFfi(emptyList(), emptyList(), emptyList(), emptyList(), listOf(LockoutFfi(i, 9.toUShort(), "04010203040506", "71".repeat(8), 7.toUShort(), 3, "ab".repeat(32))))
        }
        val n = if (extraCard) 2 else 1
        h.protocol.verifyResult = res.copy(verdicts = List(n) { JournalVerdict.ACCEPTED })
        val body = (if (extraCard) "00" + "01" + "00".repeat(94) else "") + voucherEntry(kind)
        val r = h.svc.settle(body, "t")!!
        assertEquals(if (extraCard) listOf("accepted", "retry") else listOf("retry"), r.verdicts)
        assertEquals(0, h.store.events.size)
    }

    @Test fun an_accepted_0x03_with_no_voucher_hook_answers_retry() = hookless(3)
    @Test fun an_accepted_0x04_with_no_voucher_hook_answers_retry() = hookless(4)
    @Test fun an_accepted_0x05_with_no_voucher_hook_answers_retry_and_leaves_card_entries_alone() = hookless(5, extraCard = true)
}
