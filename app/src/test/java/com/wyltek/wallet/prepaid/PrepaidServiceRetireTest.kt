package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.FakeTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class PrepaidServiceRetireTest {
    private val ckb = 100_000_000uL; private val unit = 100_000uL; private val floor = 61uL * ckb
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private val customerLock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "cd".repeat(20))
    private val uid = "04958caa5c5e80"
    private fun h() = PrepaidServiceIssueLoadTest.H(1000uL, lock).also { it.protocol.locks["ckt1customer"] = customerLock }
    private suspend fun issued(h: PrepaidServiceIssueLoadTest.H, tag: FakeTag, units: Int = 250_000): PrepaidCardEntity {
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, units, null)); return h.store.cardByUid(tag.uidHex)!!
    }

    @Test fun revoke_and_reinstate_flip_state_and_log() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        assertEquals(ActionOutcome.Done, h.svc.revoke(card))
        assertEquals(CARD_REVOKED, h.store.card(card.reserveId)!!.state)
        assertTrue(h.svc.revoke(h.store.card(card.reserveId)!!) is ActionOutcome.Failed)
        assertEquals(ActionOutcome.Done, h.svc.reinstate(h.store.card(card.reserveId)!!))
        assertEquals(CARD_ACTIVE, h.store.card(card.reserveId)!!.state)
        assertEquals(listOf(KIND_REVOKE, KIND_REVOKE, KIND_REINSTATE, KIND_REINSTATE), h.store.events.filter { it.kind != KIND_ISSUE }.map { it.kind })
        assertEquals(listOf(PHASE_REQUESTED, PHASE_DONE), h.store.phases("a2"))
        assertEquals(0, h.chain.broadcasts.size - 1)
    }

    @Test fun void_without_tag_sweeps_reserve_to_merchant_and_closes() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        val walletBefore = h.chain.cellsByLock(lock).fold(0uL) { a, u -> a + u.capacity }
        assertEquals(ActionOutcome.Done, h.svc.void(card, tag = null))
        val c = h.store.card(card.reserveId)!!
        assertEquals(CARD_CLOSED, c.state); assertEquals(0, c.balanceUnits); assertNull(h.chain.cellAt(card.currentOutpoint))
        val walletAfter = h.chain.cellsByLock(lock).fold(0uL) { a, u -> a + u.capacity }
        assertTrue(walletAfter > walletBefore - 1_000_000uL && walletAfter <= walletBefore)   // everything back minus the fee
        val ev = h.store.events.filter { it.kind == KIND_VOID }
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_DONE), ev.map { it.phase })
        assertEquals(-250_000L, ev.first().amountUnits); assertTrue(ev.first().detail.contains("forfeit"))
    }

    @Test fun void_with_tag_zeroes_both_slots() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag)
        assertEquals(ActionOutcome.Done, h.svc.void(card, tag))
        assertTrue(tag.readSlots().all { it == 0.toByte() })
    }

    @Test fun void_is_refused_while_a_credit_is_pending() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid), 1000, null)
        assertTrue(h.svc.void(h.store.cardByUid(uid)!!, null) is ActionOutcome.Failed)
    }

    @Test fun cash_out_pays_customer_returns_floor_and_zeroes_tag() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)   // 100 CKB
        val pv = h.svc.inspectForRetire(tag, card)
        assertEquals(CashOutPreview(100_000, 0, false), pv)
        assertEquals(ActionOutcome.Done, h.svc.cashOut(tag, card, "ckt1customer"))
        val customerCells = h.chain.cellsByLock(customerLock)
        assertEquals(1, customerCells.size); assertEquals(100_000uL * unit, customerCells[0].capacity)
        assertEquals(CARD_CLOSED, h.store.card(card.reserveId)!!.state)
        assertTrue(tag.readSlots().all { it == 0.toByte() })
        val ev = h.store.events.filter { it.kind == KIND_CASH_OUT }
        assertTrue(ev.first().detail.contains("ckt1customer")); assertEquals(-100_000L, ev.first().amountUnits)
    }

    @Test fun cash_out_refuses_unsettled_taps_below_minimum_and_unknown_address() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)
        tag.writeSlot(SlotPickFfi.B, h.protocol.seal("", uid, CardRecordInfo(3u, card.reserveId, 99_000, 1u, h.svc.merchantIdHex)))
        assertEquals(1L, h.svc.inspectForRetire(tag, card).unsettledTaps)
        val r1 = h.svc.cashOut(tag, card, "ckt1customer")
        assertTrue(r1 is ActionOutcome.Failed && (r1 as ActionOutcome.Failed).reason.contains("sync the terminal"))
        val tag2 = FakeTag("04ebbb02cd2a81"); val small = issued(h, tag2, 50_000)
        assertTrue(h.svc.inspectForRetire(tag2, small).belowMinimum)
        val r2 = h.svc.cashOut(tag2, small, "ckt1customer")
        assertTrue(r2 is ActionOutcome.Failed && (r2 as ActionOutcome.Failed).reason.contains("minimum"))
        val r3 = h.svc.cashOut(tag2, issued(h, FakeTag("04aaaaaaaaaaaa"), 100_000), "ckt1customer")
        assertTrue(r3 is ActionOutcome.Failed && (r3 as ActionOutcome.Failed).reason.contains("different tag"))
        val tag4 = FakeTag("04bbbbbbbbbbbb"); val c4 = issued(h, tag4, 100_000)
        val r4 = h.svc.cashOut(tag4, c4, "ckt1nobody")
        assertTrue(r4 is ActionOutcome.Failed && (r4 as ActionOutcome.Failed).reason.contains("address"))
        assertEquals(CARD_ACTIVE, h.store.card(c4.reserveId)!!.state)
    }

    @Test fun repair_rewrites_a_damaged_tag_from_the_index() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 1000)
        tag.pages.indices.forEach { tag.pages[it] = ByteArray(4) }           // wiped
        h.store.updateCard(card.copy(counter = 3, settledCounter = 5))
        assertTrue(h.svc.classify(tag) is TapClass.Damaged)
        assertEquals(ActionOutcome.Done, h.svc.repair(tag, h.store.card(card.reserveId)!!))
        val slot = h.protocol.selectSlot("", uid, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(6u, slot.record!!.counter); assertEquals(1000, slot.record!!.balanceUnits)
        assertEquals(6L, h.store.card(card.reserveId)!!.counter)
        assertEquals(KIND_REPAIR, h.store.events.last().kind); assertEquals(PHASE_DONE, h.store.events.last().phase)
    }

    @Test fun adopt_funds_a_reserve_under_the_records_own_id() = runTest {
        val h = h(); val tag = FakeTag(uid)
        val rid = "b1".repeat(32)
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid, CardRecordInfo(3u, rid, 500_000, 2u, h.svc.merchantIdHex)))
        val cls = h.svc.classify(tag)
        assertTrue(cls is TapClass.Bench)
        assertEquals(ActionOutcome.Done, h.svc.adopt(tag, (cls as TapClass.Bench).record))
        val c = h.store.card(rid)!!
        assertEquals(CARD_ACTIVE, c.state); assertEquals(500_000, c.balanceUnits); assertEquals(2L, c.counter); assertEquals(0L, c.settledCounter)
        assertEquals(floor + 500_000uL * unit, h.chain.cellAt(c.currentOutpoint)!!.capacity)
        assertEquals(2u, h.protocol.selectSlot("", uid, tag.readSlots()).record!!.counter)   // tag untouched
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_DONE), h.store.phases("a1"))
        assertTrue(h.svc.classify(tag) is TapClass.Known)
    }

    @Test fun adopt_timeout_recovers_without_a_tag_write() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid); val rid = "b1".repeat(32)
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid, CardRecordInfo(3u, rid, 500_000, 2u, h.svc.merchantIdHex)))
        val rec = (h.svc.classify(tag) as TapClass.Bench).record
        assertTrue(h.svc.adopt(tag, rec) is ActionOutcome.StillConfirming)
        val c = h.store.card(rid)!!; assertEquals(CARD_ISSUING, c.state); assertNull(c.pendingUnits)
        h.chain.confirm(c.pendingTxHash!!)
        assertEquals(1, h.svc.recoverPending())
        val d = h.store.card(rid)!!
        assertEquals(CARD_ACTIVE, d.state); assertNull(d.pendingTxHash); assertEquals(500_000, d.balanceUnits)
        assertEquals(PHASE_DONE, h.store.phases("a1").last())
    }

    @Test fun void_and_cash_out_are_refused_while_a_load_is_pending_on_an_active_card() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)
        h.chain.confirmAfterPolls = null
        assertTrue(h.svc.load(tag, card, 1000) is ActionOutcome.StillConfirming)
        h.chain.confirmAfterPolls = 1
        val cur = h.store.card(card.reserveId)!!
        val v = h.svc.void(cur, null); assertTrue(v is ActionOutcome.Failed && (v as ActionOutcome.Failed).reason.contains("pending"))
        val c = h.svc.cashOut(tag, cur, "ckt1customer"); assertTrue(c is ActionOutcome.Failed && (c as ActionOutcome.Failed).reason.contains("pending"))
    }

    @Test fun void_timeout_is_recorded_and_closes_on_recovery() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        h.chain.confirmAfterPolls = null
        val r = h.svc.void(card, null); assertTrue(r is ActionOutcome.StillConfirming)
        val hash = (r as ActionOutcome.StillConfirming).txHash
        val parked = h.store.card(card.reserveId)!!
        assertEquals(KIND_VOID, parked.pendingKind); assertEquals(CARD_ACTIVE, parked.state); assertEquals(hash, parked.pendingTxHash)
        h.chain.confirm(hash)
        assertEquals(1, h.svc.recoverPending())
        val c = h.store.card(card.reserveId)!!
        assertEquals(CARD_CLOSED, c.state); assertEquals(0, c.balanceUnits); assertNull(c.pendingTxHash); assertNull(c.pendingKind)
        assertEquals(PHASE_DONE, h.store.phases("a2").last())
    }

    @Test fun cash_out_timeout_closes_on_resume_and_wipes_tag() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)
        h.chain.confirmAfterPolls = null
        val r = h.svc.cashOut(tag, card, "ckt1customer"); assertTrue(r is ActionOutcome.StillConfirming)
        assertEquals(KIND_CASH_OUT, h.store.card(card.reserveId)!!.pendingKind)
        h.chain.confirm((r as ActionOutcome.StillConfirming).txHash)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.card(card.reserveId)!!))
        val c = h.store.card(card.reserveId)!!
        assertEquals(CARD_CLOSED, c.state); assertEquals(0, c.balanceUnits); assertNull(c.pendingTxHash)
        assertTrue(tag.readSlots().all { it == 0.toByte() })
    }

    @Test fun cash_out_refuses_when_the_tag_cannot_be_read() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)
        tag.failNextRead = true
        val r = h.svc.cashOut(tag, card, "ckt1customer")
        assertTrue(r is ActionOutcome.Failed && (r as ActionOutcome.Failed).reason.contains("could not read"))
        assertEquals(CARD_ACTIVE, h.store.card(card.reserveId)!!.state)
    }

    @Test fun adopt_recovery_waits_when_the_reserve_cell_is_not_visible() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid); val rid = "b1".repeat(32)
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid, CardRecordInfo(3u, rid, 500_000, 2u, h.svc.merchantIdHex)))
        val rec = (h.svc.classify(tag) as TapClass.Bench).record
        assertTrue(h.svc.adopt(tag, rec) is ActionOutcome.StillConfirming)
        val c = h.store.card(rid)!!
        h.chain.confirm(c.pendingTxHash!!)
        h.chain.status(c.pendingTxHash!!)                                   // applies the tx to the fake UTXO set
        val cell = h.chain.cells.remove(c.currentOutpoint)!!
        assertEquals(0, h.svc.recoverPending())
        assertEquals(CARD_ISSUING, h.store.card(rid)!!.state); assertEquals(0, h.store.card(rid)!!.balanceUnits)
        h.chain.cells[c.currentOutpoint] = cell
        assertEquals(1, h.svc.recoverPending())
        assertEquals(500_000, h.store.card(rid)!!.balanceUnits); assertEquals(CARD_ACTIVE, h.store.card(rid)!!.state)
    }

    @Test fun repair_refuses_a_readable_tag() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 1000)
        val r = h.svc.repair(tag, card)
        assertTrue(r is ActionOutcome.Failed && (r as ActionOutcome.Failed).reason.contains("readable"))
    }

    @Test fun void_timeout_gives_up_only_on_explicit_unknown() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        h.chain.confirmAfterPolls = null
        val hash = (h.svc.void(card, null) as ActionOutcome.StillConfirming).txHash
        h.clockOffsetMs = 31L * 60 * 1000
        h.chain.throwOnStatusAlways = true                     // an outage is never "unknown"
        assertEquals(0, h.svc.recoverPending())
        assertEquals(KIND_VOID, h.store.card(card.reserveId)!!.pendingKind)
        h.chain.throwOnStatusAlways = false
        h.chain.statusQueue[hash] = ArrayDeque(listOf(com.wyltek.wallet.core.chain.TxStatus.UNKNOWN))
        assertEquals(1, h.svc.recoverPending())
        val c = h.store.card(card.reserveId)!!
        assertNull(c.pendingTxHash); assertNull(c.pendingKind); assertEquals(CARD_ACTIVE, c.state)
        assertEquals(PHASE_FAILED, h.store.phases("a2").last())
    }

    private fun inputKeys(j: kotlinx.serialization.json.JsonObject) = j["inputs"]!!.jsonArray.map { i ->
        val po = i.jsonObject["previous_output"]!!.jsonObject
        po["tx_hash"]!!.jsonPrimitive.content + ":" + po["index"]!!.jsonPrimitive.content.removePrefix("0x").toUInt(16)
    }

    @Test fun void_marker_is_persisted_before_broadcast_and_survives_process_death() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        h.chain.confirmAfterPolls = null
        val job = launch { h.svc.void(card, null) }
        runCurrent(); job.cancelAndJoin()                        // the page went away mid-wait / the process died
        val hash = h.chain.broadcasts.last().first
        val row = h.store.card(card.reserveId)!!
        assertEquals(KIND_VOID, row.pendingKind); assertEquals(hash, row.pendingTxHash); assertEquals("a2", row.pendingActionId)
        assertNotNull(row.pendingSince); assertFalse(row.pendingCommitted)
        h.chain.confirm(hash)
        assertEquals(1, h.svc.recoverPending())
        val c = h.store.card(card.reserveId)!!
        assertEquals(CARD_CLOSED, c.state); assertEquals(0, c.balanceUnits); assertNull(c.pendingTxHash); assertNull(c.pendingKind)
    }

    @Test fun cash_out_marker_is_persisted_before_broadcast_and_survives_process_death() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)
        h.chain.confirmAfterPolls = null
        val job = launch { h.svc.cashOut(tag, card, "ckt1customer") }
        runCurrent(); job.cancelAndJoin()
        val hash = h.chain.broadcasts.last().first
        val row = h.store.card(card.reserveId)!!
        assertEquals(KIND_CASH_OUT, row.pendingKind); assertEquals(hash, row.pendingTxHash)
        h.protocol.verifyResult = com.wyltek.wallet.core.native.BatchResultFfi(listOf(com.wyltek.wallet.core.native.JournalVerdict.UNKNOWN_CARD), emptyList())
        val p = h.svc.settle("00".repeat(20) + card.reserveId + "00".repeat(44), "t")!!   // a pending retire is not settleable
        assertTrue(h.protocol.lastCards.none { it.reserveIdHex == card.reserveId }); assertEquals(listOf("retry"), p.verdicts)
        h.chain.confirm(hash)
        assertEquals(1, h.svc.recoverPending())
        assertEquals(CARD_CLOSED, h.store.card(card.reserveId)!!.state)
        assertEquals(1, h.chain.cellsByLock(customerLock).size)
    }

    @Test fun refused_void_leaves_no_marker() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        h.chain.refuseBroadcast = true
        assertTrue(h.svc.void(card, null) is ActionOutcome.Failed)
        val c = h.store.card(card.reserveId)!!
        assertEquals(CARD_ACTIVE, c.state); assertNull(c.pendingTxHash); assertNull(c.pendingKind)
    }

    @Test fun void_is_allowed_over_a_committed_credit_and_clears_it() = runTest {
        val h = h(); val tag = FakeTag(uid); tag.failAfterPages = 5
        assertTrue(h.svc.issue(tag, 1000, null) is ActionOutcome.AwaitingTag)
        val card = h.store.cardByUid(uid)!!
        assertTrue(card.pendingCommitted); assertEquals(1000, card.pendingUnits)
        assertEquals(ActionOutcome.Done, h.svc.void(card, null))
        val c = h.store.card(card.reserveId)!!
        assertEquals(CARD_CLOSED, c.state); assertEquals(0, c.balanceUnits)
        assertNull(c.pendingTxHash); assertNull(c.pendingUnits); assertNull(c.pendingKind); assertFalse(c.pendingCommitted); assertNull(c.pendingTagCounter)
        assertNull(h.chain.cellAt(card.currentOutpoint))
        assertTrue(card.currentOutpoint in inputKeys(h.chain.broadcasts.last().second))
    }

    private suspend fun terminalRefusal(h: PrepaidServiceIssueLoadTest.H, tag: FakeTag): PrepaidCardEntity {
        assertEquals(ActionOutcome.Done, h.svc.load(tag, issued(h, tag, 1000), 100))
        val c0 = h.store.cardByUid(uid)!!
        h.store.updateCard(c0.copy(pendingUnits = 100, pendingTxHash = c0.currentOutpoint.substringBefore(":"), pendingCommitted = true,
            pendingActionId = "ax", pendingKind = KIND_LOAD, pendingTagCounter = 1, pendingTagBalance = 1100))
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid, CardRecordInfo(3u, c0.reserveId, 1000, 2u, h.svc.merchantIdHex)))
        val out = h.svc.resume(tag, h.store.cardByUid(uid)!!)
        assertTrue(out.toString(), out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("used at a terminal"))
        return h.store.cardByUid(uid)!!
    }

    @Test fun accept_tag_as_is_resolves_the_terminal_refusal() = runTest {
        val h = h(); val tag = FakeTag(uid); val stuck = terminalRefusal(h, tag)
        val before = h.store.events.size
        assertEquals(ActionOutcome.Done, h.svc.acceptTagAsIs(tag, stuck))
        val c = h.store.card(stuck.reserveId)!!
        assertEquals(2L, c.counter); assertEquals(1100, c.balanceUnits); assertEquals(CARD_ACTIVE, c.state)
        assertNull(c.pendingTxHash); assertNull(c.pendingUnits); assertNull(c.pendingKind); assertFalse(c.pendingCommitted); assertNull(c.pendingTagCounter)
        val rows = h.store.events.drop(before)
        assertEquals(listOf(PHASE_REQUESTED, PHASE_DONE), rows.map { it.phase })
        assertTrue(rows.all { it.kind == KIND_REPAIR && it.actionId == "ax" })
        assertEquals("accepted tag as-is after terminal use", rows.last().detail)
        assertEquals(2u, h.protocol.selectSlot("", uid, tag.readSlots()).record!!.counter)   // tag untouched
    }

    @Test fun accept_tag_as_is_is_refused_without_a_committed_credit_or_with_a_foreign_record() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 1000)
        assertTrue(h.svc.acceptTagAsIs(tag, card) is ActionOutcome.Failed)
        h.store.updateCard(card.copy(pendingUnits = 5, pendingTxHash = "0x" + "11".repeat(32), pendingCommitted = true, pendingActionId = "ax", pendingKind = KIND_LOAD))
        tag.writeSlot(SlotPickFfi.B, h.protocol.seal("", uid, CardRecordInfo(3u, "c1".repeat(32), 1000, 9u, h.svc.merchantIdHex)))
        val r = h.svc.acceptTagAsIs(tag, h.store.card(card.reserveId)!!)
        assertTrue(r.toString(), r is ActionOutcome.Failed && (r as ActionOutcome.Failed).reason.contains("another reserve"))
        assertNotNull(h.store.card(card.reserveId)!!.pendingTxHash)
    }
}
