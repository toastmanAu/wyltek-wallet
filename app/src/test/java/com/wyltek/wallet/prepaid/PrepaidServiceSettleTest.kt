package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.FakeTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

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
            nets = listOf(CardNetFfi(c1.reserveId, 50_000, 7u, 2u), CardNetFfi(c2.reserveId, -10_000, 3u, 1u)))
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
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED), listOf(CardNetFfi(c1.reserveId, 0, 9u, 2u)))
        val r = h.svc.settle(entries(c1.reserveId, c1.reserveId), "t")!!
        assertNull(r.txHash); assertEquals(listOf("accepted", "accepted"), r.verdicts)
        assertEquals(9L, h.store.card(c1.reserveId)!!.settledCounter); assertEquals(1, h.chain.broadcasts.size)
    }

    @Test fun failed_transaction_turns_accepted_into_retry_and_advances_nothing() = runTest {
        val h = h(); h.svc.issue(FakeTag("04000000000001"), 300_000, null)
        val c1 = h.store.allCards().single()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.BAD_MAC), listOf(CardNetFfi(c1.reserveId, 1000, 2u, 1u)))
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
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.UNKNOWN_CARD, JournalVerdict.UNKNOWN_CARD, JournalVerdict.ACCEPTED), listOf(CardNetFfi(active.reserveId, 0, 1u, 1u)))
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
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.UNKNOWN_CARD), emptyList())
        val r = h.svc.settle(entries(card.reserveId), "t")!!
        assertEquals(emptyList<String>(), h.protocol.lastCards.map { it.reserveIdHex })
        assertEquals(listOf("retry"), r.verdicts)
    }

    @Test fun bad_length_is_null() = runTest {
        val h = h()
        h.protocol.verifyResult = BatchResultFfi(emptyList(), emptyList())
        assertNull(h.svc.settle("00".repeat(95), "t"))
        assertNotNull(h.svc.settle("", "t"))
    }
}
