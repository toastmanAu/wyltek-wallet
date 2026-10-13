package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

@OptIn(ExperimentalCoroutinesApi::class)
class PrepaidServiceIssueLoadTest {
    private val ckb = 100_000_000uL
    private val unit = 100_000uL
    private val floor = 61uL * ckb
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private val uid1 = "04958caa5c5e80"; private val uid2 = "04ebbb02cd2a81"

    class H(walletCkb: ULong = 1000uL, val lock: LockScript) {
        val store = InMemoryPrepaidStore(); val chain = FakeChain(); val signer = FakeSigner(lock); val protocol = FakeProtocol()
        var now = 1_000L; var ids = 0; var clockOffsetMs = 0L
        val keys = PrepaidKeys(load = { null }, store = { _, _ -> }, random = { ByteArray(16) { 1 } })
        val svc = PrepaidService(store, chain, signer, protocol, keys, clock = { now++ + clockOffsetMs }, newActionId = { "a${++ids}" }, pollDelayMs = 3_000, pollBudgetMs = 90_000)
        init { chain.add(Utxo(OutPoint("0x" + "aa".repeat(32), 0u), walletCkb * 100_000_000uL, lock)) }
        fun phases(actionId: String) = store.phases(actionId)
    }
    private fun h(walletCkb: ULong = 1000uL) = H(walletCkb, lock)

    @Test fun issue_happy_path_writes_tag_and_logs_phases() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
        val out = h.svc.issue(tag, 250_000, "Card 1")
        assertEquals(ActionOutcome.Done, out)
        val card = h.store.cardByUid(uid1)!!
        assertEquals(CARD_ACTIVE, card.state); assertEquals(250_000, card.balanceUnits); assertEquals(0L, card.counter)
        assertNull(card.pendingUnits); assertNull(card.pendingTxHash); assertEquals("Card 1", card.label)
        assertEquals(floor + 250_000uL * unit, h.chain.cellAt(card.currentOutpoint)!!.capacity)
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(250_000, slot.record!!.balanceUnits); assertEquals(0u, slot.record!!.counter)
        assertTrue(tag.slot(SlotPickFfi.B).all { it == 0.toByte() })
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.phases("a1"))
        assertTrue(h.store.events.all { it.kind == KIND_ISSUE && it.reserveId == card.reserveId })
        assertTrue(h.svc.classify(tag) is TapClass.Known)
    }

    @Test fun issue_refused_broadcast_leaves_no_card_and_logs_failed() = runTest {
        val h = h(); h.chain.refuseBroadcast = true
        val out = h.svc.issue(FakeTag(uid1), 1000, null)
        assertTrue(out is ActionOutcome.Failed)
        assertNull(h.store.cardByUid(uid1))
        assertEquals(listOf(PHASE_REQUESTED, PHASE_FAILED), h.phases("a1"))
    }

    @Test fun issue_timeout_keeps_pending_then_resume_completes() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid1)
        val out = h.svc.issue(tag, 1000, null)
        assertTrue(out is ActionOutcome.StillConfirming)
        val card = h.store.cardByUid(uid1)!!
        assertEquals(CARD_ISSUING, card.state); assertEquals(1000, card.pendingUnits); assertFalse(card.pendingCommitted)
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST), h.phases("a1"))
        assertTrue(h.svc.classify(tag) is TapClass.Resume)
        assertTrue(h.svc.resume(tag, card) is ActionOutcome.StillConfirming)
        h.chain.confirm(card.pendingTxHash!!)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.phases("a1"))
        assertEquals(CARD_ACTIVE, h.store.cardByUid(uid1)!!.state)
    }

    @Test fun issue_rejected_after_broadcast_removes_the_issuing_row() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        val hash = h.store.cardByUid(uid1)!!.pendingTxHash!!
        h.chain.reject(hash)
        assertTrue(h.svc.resume(tag, h.store.cardByUid(uid1)!!) is ActionOutcome.Failed)
        assertNull(h.store.cardByUid(uid1))
        assertEquals(PHASE_FAILED, h.phases("a1").last())
    }

  @Test fun torn_write_after_commit_resumes_on_next_tap() = runTest {
        val h = h(); val tag = FakeTag(uid1); tag.failAfterPages = 5
        val out = h.svc.issue(tag, 1000, null)
        assertTrue(out is ActionOutcome.AwaitingTag)
        val card = h.store.cardByUid(uid1)!!
        assertEquals(CARD_ACTIVE, card.state); assertEquals(1000, card.pendingUnits); assertTrue(card.pendingCommitted)
        assertEquals(1000, card.balanceUnits)   // chain view already credited
        assertEquals(PHASE_FAILED, h.phases("a1").last())
        tag.present = true
        assertTrue(h.svc.classify(tag) is TapClass.Resume)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(1000, slot.record!!.balanceUnits)
        assertTrue(h.phases("a1").takeLast(2) == listOf(PHASE_WRITTEN, PHASE_DONE))
    }

    @Test fun load_happy_path_grows_reserve_and_advances_counter() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 250_000, null)
        val before = h.store.cardByUid(uid1)!!
        assertEquals(ActionOutcome.Done, h.svc.load(tag, before, 100_000))
        val after = h.store.cardByUid(uid1)!!
        assertEquals(350_000, after.balanceUnits); assertEquals(1L, after.counter); assertNotEquals(before.currentOutpoint, after.currentOutpoint)
        assertNull(h.chain.cellAt(before.currentOutpoint)); assertEquals(floor + 350_000uL * unit, h.chain.cellAt(after.currentOutpoint)!!.capacity)
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.B, slot.pick); assertEquals(350_000, slot.record!!.balanceUnits); assertEquals(1u, slot.record!!.counter)
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.phases("a2"))
        assertEquals(KIND_LOAD, h.store.events.last().kind)
    }

    @Test fun load_uses_the_tag_balance_when_terminal_taps_are_unsettled() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        val card = h.store.cardByUid(uid1)!!
        // a POS wrote counter 5 / balance 900 into slot B since
        tag.writeSlot(SlotPickFfi.B, h.protocol.seal("", uid1, CardRecordInfo(3u, card.reserveId, 900, 5u, h.svc.merchantIdHex)))
        assertEquals(ActionOutcome.Done, h.svc.load(tag, card, 100))
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(1000, slot.record!!.balanceUnits); assertEquals(6u, slot.record!!.counter)
        val after = h.store.cardByUid(uid1)!!
        assertEquals(1100, after.balanceUnits); assertEquals(6L, after.counter)   // index = chain view; counter follows the tag
    }

    @Test fun load_never_spends_another_cards_reserve() = runTest {
        val h = h(1000uL); val t1 = FakeTag(uid1); val t2 = FakeTag(uid2)
        assertEquals(ActionOutcome.Done, h.svc.issue(t1, 250_000, null))
        assertEquals(ActionOutcome.Done, h.svc.issue(t2, 250_000, null))
        val c1 = h.store.cardByUid(uid1)!!; val c2 = h.store.cardByUid(uid2)!!
        val out = h.svc.load(t1, c1, 310_000)   // wallet change (~378 CKB) < 310 + fee + 81 change; c2's 311 CKB reserve must NOT be touched
        assertTrue(out.toString(), out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("insufficient"))
        assertEquals(2, h.chain.broadcasts.size)
        assertNotNull(h.chain.cellAt(c2.currentOutpoint))
        assertNull(h.store.cardByUid(uid1)!!.pendingTxHash)
    }

    @Test fun resume_refuses_a_different_uid() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid1), 1000, null)
        val card = h.store.cardByUid(uid1)!!; h.chain.confirm(card.pendingTxHash!!)
        val n = h.store.events.size
        val out = h.svc.resume(FakeTag(uid2), card)
        assertTrue(out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("different tag"))
        assertEquals(n + 1, h.store.events.size)              // only the COMMITTED row was added by the status check
        assertEquals(1000, h.store.cardByUid(uid1)!!.pendingUnits)
        assertNull(h.store.cardByUid(uid2))
    }

    @Test fun second_action_while_busy_is_refused_without_rows() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        val job = launch { h.svc.issue(FakeTag(uid1), 1000, null) }
        runCurrent()
        val n = h.store.events.size
        assertEquals(ActionOutcome.Failed("busy"), h.svc.issue(FakeTag(uid2), 1000, null))
        assertEquals(n, h.store.events.size); assertNull(h.store.cardByUid(uid2))
        job.cancel()
    }

    @Test fun recover_pending_marks_committed_without_a_tag() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid1), 1000, null)
        val card = h.store.cardByUid(uid1)!!; h.chain.confirm(card.pendingTxHash!!)
        assertEquals(1, h.svc.recoverPending())
        val c = h.store.cardByUid(uid1)!!
        assertTrue(c.pendingCommitted); assertEquals(CARD_ACTIVE, c.state); assertEquals(1000, c.pendingUnits); assertEquals(1000, c.balanceUnits)
        assertEquals(PHASE_COMMITTED, h.phases("a1").last())
        assertEquals(0, h.svc.recoverPending())   // idempotent: nothing new to do
    }

    @Test fun card_limit_and_duplicate_uid_are_refused_before_any_row() = runTest {
        val h = h(); 
        repeat(64) { i -> h.store.cards["r$i"] = PrepaidCardEntity("r$i", "u$i", null, "x:0", "x:0", 0, 0, 0, CARD_ACTIVE, issuedAt = 0, updatedAt = 0) }
        val out = h.svc.issue(FakeTag(uid1), 1000, null)
        assertTrue(out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("limit"))
        assertTrue(h.store.events.isEmpty())
        val out2 = h.svc.issue(FakeTag("u3"), 1000, null)
        assertTrue(out2 is ActionOutcome.Failed && (out2 as ActionOutcome.Failed).reason.contains("already"))
    }

    @Test fun load_is_refused_while_a_credit_is_pending_or_card_not_active() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        val out = h.svc.load(tag, h.store.cardByUid(uid1)!!, 10)
        assertTrue(out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("pending"))
    }

    private suspend fun pendAgain(h: H, uid: String, kind: String = KIND_ISSUE, units: Int = 1000) {
        val c = h.store.cardByUid(uid)!!
        h.store.updateCard(c.copy(pendingUnits = units, pendingTxHash = c.firstOutpoint.substringBefore(":"), pendingCommitted = true,
            pendingActionId = "ax", pendingTagCounter = 0, pendingTagBalance = units, pendingKind = kind))
    }

    @Test fun resume_after_tear_during_zero_slot_does_not_double_credit() = runTest {
        val h = h(); val tag = FakeTag(uid1); tag.tearOnWriteCall = 2   // first slot write completes, zeroSlot tears
        assertTrue(h.svc.issue(tag, 1000, null) is ActionOutcome.AwaitingTag)
        tag.present = true
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        val rec = h.protocol.selectSlot("", uid1, tag.readSlots()).record!!
        assertEquals(1000, rec.balanceUnits); assertEquals(0u, rec.counter)
        val c = h.store.cardByUid(uid1)!!
        assertEquals(1000, c.balanceUnits); assertNull(c.pendingUnits); assertNull(c.pendingTagCounter)
        assertEquals(listOf(PHASE_WRITTEN, PHASE_DONE), h.phases("a1").takeLast(2))
    }

    @Test fun resume_after_crash_following_the_write_does_not_double_credit() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, 1000, null))
        pendAgain(h, uid1)
        val writes = tag.pageWrites; tag.failNextRead = true
        assertTrue(h.svc.resume(tag, h.store.cardByUid(uid1)!!) is ActionOutcome.AwaitingTag)   // read glitch
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        assertEquals(writes + 16, tag.pageWrites)   // only the best-effort zero of the other slot
        val rec = h.protocol.selectSlot("", uid1, tag.readSlots()).record!!
        assertEquals(1000, rec.balanceUnits); assertEquals(0u, rec.counter)
        assertNull(h.store.cardByUid(uid1)!!.pendingTxHash)
    }

    @Test fun load_timeout_then_resume_is_audited_as_load() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        h.chain.confirmAfterPolls = null
        assertTrue(h.svc.load(tag, h.store.cardByUid(uid1)!!, 50) is ActionOutcome.StillConfirming)
        val card = h.store.cardByUid(uid1)!!; h.chain.confirm(card.pendingTxHash!!)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, card))
        assertTrue(h.store.events.filter { it.actionId == "a2" }.all { it.kind == KIND_LOAD })
        val rec = h.protocol.selectSlot("", uid1, tag.readSlots()).record!!
        assertEquals(1050, rec.balanceUnits); assertEquals(1u, rec.counter)
    }

    @Test fun resume_refuses_an_ambiguous_tag() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        val card = h.store.cardByUid(uid1)!!
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid1, CardRecordInfo(0u, card.reserveId, 1, 7u, h.svc.merchantIdHex)))
        tag.writeSlot(SlotPickFfi.B, h.protocol.seal("", uid1, CardRecordInfo(0u, card.reserveId, 2, 7u, h.svc.merchantIdHex)))
        pendAgain(h, uid1)
        val out = h.svc.resume(tag, h.store.cardByUid(uid1)!!)
        assertTrue(out.toString(), out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("ambiguous"))
        assertNotNull(h.store.cardByUid(uid1)!!.pendingTxHash)
    }

    @Test fun recover_gives_up_on_a_transaction_unknown_for_30_minutes() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid1), 1000, null)
        val hash = h.store.cardByUid(uid1)!!.pendingTxHash!!
        h.chain.statusQueue[hash] = ArrayDeque(listOf(com.wyltek.wallet.core.chain.TxStatus.UNKNOWN))
        assertEquals(0, h.svc.recoverPending())                 // not old enough
        h.clockOffsetMs = 31L * 60 * 1000
        assertEquals(1, h.svc.recoverPending())
        assertNull(h.store.cardByUid(uid1)); assertEquals(PHASE_FAILED, h.phases("a1").last())
    }

    @Test fun status_rpc_errors_during_polling_do_not_abort() = runTest {
        val h = h(); h.chain.throwOnStatusOnce = true
        assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid1), 1000, null))
    }

    @Test fun load_on_revoked_card_is_refused() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        h.svc.revoke(h.store.cardByUid(uid1)!!)
        val out = h.svc.load(tag, h.store.cardByUid(uid1)!!, 10)
        assertTrue(out.toString(), out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("revoked"))
    }

    @Test fun repair_with_pending_torn_then_resume_does_not_double_credit() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid1)
        assertTrue(h.svc.issue(tag, 1000, null) is ActionOutcome.StillConfirming)
        val hash = h.store.cardByUid(uid1)!!.pendingTxHash!!; h.chain.confirm(hash)
        assertEquals(1, h.svc.recoverPending())
        tag.pages.forEach { it.fill(0) }
        tag.tearOnWriteCall = 2
        assertTrue(h.svc.repair(tag, h.store.cardByUid(uid1)!!) is ActionOutcome.Failed)
        tag.present = true
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        assertEquals(1000, h.protocol.selectSlot("", uid1, tag.readSlots()).record!!.balanceUnits)
        val c = h.store.cardByUid(uid1)!!; assertNull(c.pendingTxHash); assertEquals(1000, c.balanceUnits)
    }

    @Test fun give_up_never_fires_on_rpc_errors() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid1), 1000, null)
        val hash = h.store.cardByUid(uid1)!!.pendingTxHash!!
        h.clockOffsetMs = 31L * 60 * 1000
        h.chain.throwOnStatusAlways = true
        assertEquals(0, h.svc.recoverPending())
        assertEquals(CARD_ISSUING, h.store.cardByUid(uid1)!!.state); assertNotNull(h.store.cardByUid(uid1)!!.pendingTxHash)
        h.chain.throwOnStatusAlways = false
        h.chain.statusQueue[hash] = ArrayDeque(listOf(com.wyltek.wallet.core.chain.TxStatus.UNKNOWN))
        assertEquals(1, h.svc.recoverPending())
        assertNull(h.store.cardByUid(uid1))
    }

    @Test fun issue_write_then_terminal_tap_then_resume_finalises_once() = runTest {
        val h = h(); val tag = FakeTag(uid1); tag.tearOnWriteCall = 2
        assertTrue(h.svc.issue(tag, 1000, null) is ActionOutcome.AwaitingTag)
        val card = h.store.cardByUid(uid1)!!
        tag.present = true
        tag.writeSlot(SlotPickFfi.B, h.protocol.seal("", uid1, CardRecordInfo(3u, card.reserveId, 900, 1u, h.svc.merchantIdHex)))
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        val rec = h.protocol.selectSlot("", uid1, tag.readSlots()).record!!
        assertEquals(900, rec.balanceUnits); assertEquals(1u, rec.counter)
        val c = h.store.cardByUid(uid1)!!; assertEquals(1000, c.balanceUnits); assertNull(c.pendingTxHash)
    }

    @Test fun load_write_then_terminal_tap_then_resume_is_refused() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        tag.failNextRead = false
        // make the readback (second read inside writePending) throw: arm after the first read via a tear on a no-op is not possible,
        // so simulate the interrupted readback by re-pending the load after its write landed.
        assertEquals(ActionOutcome.Done, h.svc.load(tag, h.store.cardByUid(uid1)!!, 100))
        val c0 = h.store.cardByUid(uid1)!!
        h.store.updateCard(c0.copy(pendingUnits = 100, pendingTxHash = c0.currentOutpoint.substringBefore(":"), pendingCommitted = true,
            pendingActionId = "ax", pendingKind = KIND_LOAD, pendingTagCounter = 1, pendingTagBalance = 1100))
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid1, CardRecordInfo(3u, c0.reserveId, 1000, 2u, h.svc.merchantIdHex)))
        val out = h.svc.resume(tag, h.store.cardByUid(uid1)!!)
        assertTrue(out.toString(), out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("terminal"))
        assertNotNull(h.store.cardByUid(uid1)!!.pendingTxHash)
    }

    @Test fun hashes_and_outpoints_are_0x_although_the_signer_returns_bare_hex() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        assertFalse(h.signer.build(emptyList(), emptyList()).txHashHex.startsWith("0x"))   // the node shape rust-core returns
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, 1000, null))
        assertEquals(ActionOutcome.Done, h.svc.load(tag, h.store.cardByUid(uid1)!!, 100))
        val c = h.store.cardByUid(uid1)!!
        assertTrue(c.firstOutpoint.startsWith("0x") && c.currentOutpoint.startsWith("0x"))
        assertNotNull(h.chain.cellAt(c.currentOutpoint))
        assertTrue(h.chain.broadcasts.all { it.first.startsWith("0x") })
        assertTrue(h.store.events.mapNotNull { it.txHash }.all { it.startsWith("0x") && it == it.lowercase() })
        assertTrue(h.svc.excludedOutpoints().contains(c.currentOutpoint))
    }

    private fun inputKeys(j: kotlinx.serialization.json.JsonObject) = j["inputs"]!!.jsonArray.map { i ->
        val po = i.jsonObject["previous_output"]!!.jsonObject
        po["tx_hash"]!!.jsonPrimitive.content + ":" + po["index"]!!.jsonPrimitive.content.removePrefix("0x").toUInt(16)
    }

    @Test fun an_unconfirmed_loads_successor_reserve_is_never_a_fee_input() = runTest {
        val h = h(); val t1 = FakeTag(uid1)
        assertEquals(ActionOutcome.Done, h.svc.issue(t1, 1000, null))
        h.chain.confirmAfterPolls = null
        assertTrue(h.svc.load(t1, h.store.cardByUid(uid1)!!, 1000) is ActionOutcome.StillConfirming)
        val hashX = h.store.cardByUid(uid1)!!.pendingTxHash!!
        h.chain.confirm(hashX); h.chain.status(hashX)              // commits while the page is open; nothing recovered yet
        assertNotNull(h.chain.cellAt("$hashX:0"))                   // the smallest pure-CKB cell in the wallet now
        h.chain.confirmAfterPolls = 1
        h.svc.issue(FakeTag(uid2), 1000, null)
        val y = h.chain.broadcasts.last()
        assertNotEquals(hashX, y.first)
        assertFalse(inputKeys(y.second).toString(), "$hashX:0" in inputKeys(y.second))
        assertTrue("$hashX:0" in h.svc.excludedOutpoints())
    }
}
