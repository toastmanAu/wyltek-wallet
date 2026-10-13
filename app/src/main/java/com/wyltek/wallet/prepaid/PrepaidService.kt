package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * Every prepaid flow (D3 spec §5–§6). One action at a time (a mutex; a second caller gets
 * `Failed("busy")`). Chain first, tag second: a credit is committed on chain before the tag is
 * written, and a credit the tag never received stays in `pendingUnits` until the next tap.
 * Audit rows are appended at every phase and never touched again.
 */
class PrepaidService(
    private val store: PrepaidStore,
    private val chain: ChainPort,
    private val signer: SignerPort,
    private val protocol: Protocol,
    private val keys: PrepaidKeys,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newActionId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val pollDelayMs: Long = 3_000,
    private val pollBudgetMs: Long = 90_000,
) {
    companion object {
        const val MAX_ACTIVE_CARDS = 64
        const val UNIT_SHANNONS = 100_000L
    }

    val lock: LockScript get() = signer.lock
    val merchantIdHex: String by lazy { protocol.merchantId(signer.lock) }
    private val kHex: String get() = keys.kDebitHex()
    private val mutex = Mutex()

    // ---------------------------------------------------------------- audit
    internal class Audit(val actionId: String, val kind: String, val uid: String, val reserveId: String?, val amount: Long,
                         val balanceBefore: Int, val counterBefore: Long)

    internal suspend fun log(a: Audit, phase: String, balanceAfter: Int = a.balanceBefore, counterAfter: Long = a.counterBefore,
                             txHash: String? = null, detail: String = "") {
        store.event(PrepaidEventEntity(atMs = clock(), actionId = a.actionId, kind = a.kind, phase = phase, reserveId = a.reserveId,
            uid = a.uid, amountUnits = a.amount, balanceBefore = a.balanceBefore, balanceAfter = balanceAfter,
            counterBefore = a.counterBefore, counterAfter = counterAfter, txHash = txHash, detail = detail))
    }

    /** Runs [block] if no other action is in flight; null means busy. */
    internal suspend fun <T> exclusive(block: suspend () -> T): T? =
        if (mutex.tryLock()) try { block() } finally { mutex.unlock() } else null

    private fun busy() = ActionOutcome.Failed("busy")

    // ---------------------------------------------------------------- classify
    suspend fun classify(tag: TagPort): TapClass {
        val raw = try { tag.readSlots() } catch (e: TagLostException) { return TapClass.Refused("tag lost while reading") }
        val slot = protocol.selectSlot(kHex, tag.uidHex, raw)
        val byUid = store.cardByUid(tag.uidHex)
        val byReserve = slot.record?.let { store.card(it.reserveIdHex) }
        return TapClassifier.classify(tag.uidHex, slot, byUid, byReserve, merchantIdHex)
    }

    // ---------------------------------------------------------------- chain helpers
    internal sealed class TxResult {
        data class Committed(val txHash: String) : TxResult()
        data class Timeout(val txHash: String) : TxResult()
        data class Refused(val reason: String) : TxResult()
        data class Rejected(val txHash: String) : TxResult()
    }

    /** Every reserve cell in the index; never a fee input for another card's transaction. */
    internal suspend fun excludedOutpoints(): Set<String> = store.allCards().map { it.currentOutpoint }.toSet()

    internal suspend fun awaitCommit(txHash: String): TxStatus {
        var waited = 0L
        while (true) {
            when (val s = chain.status(txHash)) { TxStatus.CONFIRMED, TxStatus.REJECTED -> return s; else -> {} }
            if (waited >= pollBudgetMs) return TxStatus.PENDING
            delay(pollDelayMs); waited += pollDelayMs
        }
    }

    internal suspend fun broadcastAndAwait(a: Audit, planned: PlannedTx, built: BuiltTransaction, progress: (Phase) -> Unit): TxResult {
        val json = CkbTxJson.build(planned.inputs, planned.outputs, signer.cellDep, signer.witness0(built.txHashHex, planned.inputs.size))
        if (!chain.broadcast(json, built.txHashHex)) {
            log(a, PHASE_FAILED, detail = "broadcast refused"); progress(Phase.FAILED)
            return TxResult.Refused("the node refused the transaction")
        }
        log(a, PHASE_BROADCAST, txHash = built.txHashHex); progress(Phase.BROADCAST)
        return when (awaitCommit(built.txHashHex)) {
            TxStatus.CONFIRMED -> { log(a, PHASE_COMMITTED, txHash = built.txHashHex); progress(Phase.COMMITTED); TxResult.Committed(built.txHashHex) }
            TxStatus.REJECTED -> { log(a, PHASE_FAILED, txHash = built.txHashHex, detail = "rejected by the node"); progress(Phase.FAILED); TxResult.Rejected(built.txHashHex) }
            else -> TxResult.Timeout(built.txHashHex)
        }
    }

    /** Which action a pending row belongs to. A committed issue awaiting its first tag write still sits on its
     *  first cell with counter 0; a load has moved the reserve to a new outpoint. */
    internal fun kindOfPending(card: PrepaidCardEntity): String = when {
        card.pendingUnits == null -> KIND_ADOPT
        card.state == CARD_ISSUING -> KIND_ISSUE
        card.firstOutpoint == card.currentOutpoint && card.counter == 0L && card.settledCounter == 0L -> KIND_ISSUE
        else -> KIND_LOAD
    }

    internal fun auditForPending(card: PrepaidCardEntity) = Audit(card.pendingActionId ?: newActionId(), kindOfPending(card), card.uid,
        card.reserveId, (card.pendingUnits ?: 0).toLong(), card.balanceUnits, card.counter)

    internal suspend fun clearPending(card: PrepaidCardEntity, activate: Boolean) {
        store.updateCard(card.copy(pendingUnits = null, pendingTxHash = null, pendingActionId = null, pendingCommitted = false,
            state = if (activate && card.state == CARD_ISSUING) CARD_ACTIVE else card.state, updatedAt = clock()))
    }

    // ---------------------------------------------------------------- tag write (the "card second" half)
    /** Writes `card.pendingUnits` onto the tag. Requires the pending transaction to be committed already. */
    internal suspend fun writePending(tag: TagPort, card: PrepaidCardEntity, a: Audit, progress: (Phase) -> Unit): ActionOutcome {
        val pending = card.pendingUnits
        if (pending == null) { clearPending(card, activate = true); log(a, PHASE_DONE, txHash = card.pendingTxHash); progress(Phase.DONE); return ActionOutcome.Done }
        if (!tag.uidHex.equals(card.uid, ignoreCase = true))
            return ActionOutcome.Failed("this is a different tag; present the card ${card.label ?: card.uid}")
        if (!tag.isPresent()) return ActionOutcome.AwaitingTag(card.reserveId)
        val raw = try { tag.readSlots() } catch (e: TagLostException) { return ActionOutcome.AwaitingTag(card.reserveId) }
        val slot = protocol.selectSlot(kHex, tag.uidHex, raw)
        val onTag = slot.record
        // Tag balance follows the tag (unsettled terminal taps live there); the index follows the chain.
        val tagBalance = (onTag?.balanceUnits ?: (card.balanceUnits - pending)) + pending
        val counter: Long = when {
            onTag != null -> onTag.counter.toLong() + 1
            a.kind == KIND_ISSUE -> 0                          // first record on a fresh tag
            else -> maxOf(card.counter, card.settledCounter) + 1
        }
        val raw64 = protocol.seal(kHex, tag.uidHex, CardRecordInfo(0u, card.reserveId, tagBalance, counter.toUInt(), merchantIdHex))
        val target = protocol.nextSlot(slot.pick)
        try {
            tag.writeSlot(target, raw64)
            if (onTag == null) tag.zeroSlot(if (target == SlotPickFfi.A) SlotPickFfi.B else SlotPickFfi.A)
        } catch (e: TagLostException) {
            log(a, PHASE_FAILED, txHash = card.pendingTxHash, detail = "write interrupted: ${e.message}"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(card.reserveId)
        }
        val back = try { tag.readSlots() } catch (e: TagLostException) {
            log(a, PHASE_FAILED, txHash = card.pendingTxHash, detail = "readback interrupted"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(card.reserveId)
        }
        val sel2 = protocol.selectSlot(kHex, tag.uidHex, back)
        val ok = sel2.record?.let { it.counter.toLong() == counter && it.balanceUnits == tagBalance && it.reserveIdHex.equals(card.reserveId, true) } ?: false
        if (!ok) {
            log(a, PHASE_FAILED, txHash = card.pendingTxHash, detail = "readback mismatch A=${hex(back, 0, 64)} B=${hex(back, 64, 128)}"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(card.reserveId)
        }
        val updated = card.copy(counter = counter, pendingUnits = null, pendingTxHash = null, pendingActionId = null, pendingCommitted = false,
            state = if (card.state == CARD_ISSUING) CARD_ACTIVE else card.state, updatedAt = clock())
        store.updateCard(updated)
        log(a, PHASE_WRITTEN, balanceAfter = updated.balanceUnits, counterAfter = counter, txHash = card.pendingTxHash); progress(Phase.WRITTEN)
        log(a, PHASE_DONE, balanceAfter = updated.balanceUnits, counterAfter = counter, txHash = card.pendingTxHash); progress(Phase.DONE)
        return ActionOutcome.Done
    }

    private fun hex(b: ByteArray, from: Int, to: Int) = b.copyOfRange(from, to).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- issue
    suspend fun issue(tag: TagPort, amountUnits: Int, label: String?, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        if (store.cardByUid(tag.uidHex) != null) return@exclusive ActionOutcome.Failed("this tag is already indexed")
        if (store.activeCount() >= MAX_ACTIVE_CARDS) return@exclusive ActionOutcome.Failed("card limit of $MAX_ACTIVE_CARDS reached")
        val plan = try { protocol.plan(PrepaidAction.ISSUE, 0, 0uL, amountUnits) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed(e.message ?: "refused") }
        val planned = try {
            PrepaidTxPlanner.plan(emptyList(), chain.cellsByLock(signer.lock), excludedOutpoints(), listOf(PrepaidTxPlanner.output(plan.successorCapacity, signer.lock)), signer.lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = signer.build(planned.inputs, planned.outputs)
        val reserveId = protocol.reserveId(built.txHashHex, 0u)
        val a = Audit(newActionId(), KIND_ISSUE, tag.uidHex, reserveId, amountUnits.toLong(), 0, 0)
        val now = clock()
        store.insertCard(PrepaidCardEntity(reserveId = reserveId, uid = tag.uidHex, label = label?.ifBlank { null },
            firstOutpoint = "${built.txHashHex}:0", currentOutpoint = "${built.txHashHex}:0", balanceUnits = 0, counter = 0, settledCounter = 0,
            state = CARD_ISSUING, pendingUnits = amountUnits, pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingCommitted = false,
            issuedAt = now, updatedAt = now))
        log(a, PHASE_REQUESTED); progress(Phase.REQUESTED)
        when (val r = broadcastAndAwait(a, planned, built, progress)) {
            is TxResult.Refused -> { store.deleteIssuing(reserveId); ActionOutcome.Failed(r.reason) }
            is TxResult.Rejected -> { store.deleteIssuing(reserveId); ActionOutcome.Failed("rejected by the node") }
            is TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is TxResult.Committed -> {
                val card = store.card(reserveId)!!.copy(state = CARD_ACTIVE, balanceUnits = plan.balanceAfter, pendingCommitted = true, updatedAt = clock())
                store.updateCard(card)
                writePending(tag, card, a, progress)
            }
        }
    } ?: busy()

    // ---------------------------------------------------------------- load
    suspend fun load(tag: TagPort, card: PrepaidCardEntity, amountUnits: Int, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        if (fresh.pendingTxHash != null) return@exclusive ActionOutcome.Failed("finish the pending credit first (tap the card)")
        if (fresh.state != CARD_ACTIVE) return@exclusive ActionOutcome.Failed("card is ${fresh.state}")
        if (!tag.uidHex.equals(fresh.uid, true)) return@exclusive ActionOutcome.Failed("this is a different tag")
        val reserve = chain.cellAt(fresh.currentOutpoint) ?: return@exclusive ActionOutcome.Failed("reserve cell not found on chain; use Inspect")
        val plan = try { protocol.plan(PrepaidAction.LOAD, fresh.balanceUnits, reserve.capacity, amountUnits) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed(e.message ?: "refused") }
        val planned = try {
            PrepaidTxPlanner.plan(listOf(reserve), chain.cellsByLock(signer.lock), excludedOutpoints() - fresh.currentOutpoint,
                listOf(PrepaidTxPlanner.output(plan.successorCapacity, signer.lock)), signer.lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = signer.build(planned.inputs, planned.outputs)
        val a = Audit(newActionId(), KIND_LOAD, fresh.uid, fresh.reserveId, amountUnits.toLong(), fresh.balanceUnits, fresh.counter)
        store.updateCard(fresh.copy(pendingUnits = amountUnits, pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingCommitted = false, updatedAt = clock()))
        log(a, PHASE_REQUESTED); progress(Phase.REQUESTED)
        when (val r = broadcastAndAwait(a, planned, built, progress)) {
            is TxResult.Refused -> { clearPending(store.card(fresh.reserveId)!!, activate = false); ActionOutcome.Failed(r.reason) }
            is TxResult.Rejected -> { clearPending(store.card(fresh.reserveId)!!, activate = false); ActionOutcome.Failed("rejected by the node") }
            is TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is TxResult.Committed -> {
                val c = store.card(fresh.reserveId)!!.copy(currentOutpoint = "${r.txHash}:0", balanceUnits = plan.balanceAfter, pendingCommitted = true, updatedAt = clock())
                store.updateCard(c)
                writePending(tag, c, a, progress)
            }
        }
    } ?: busy()

    // ---------------------------------------------------------------- resume / recover
    /** Finish a pending credit on the tag of [card]. Checks the transaction once (no 90 s wait). */
    suspend fun resume(tag: TagPort, card: PrepaidCardEntity, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        val hash = fresh.pendingTxHash ?: return@exclusive ActionOutcome.Done
        val a = auditForPending(fresh)
        val committed = fresh.pendingCommitted || when (chain.status(hash)) {
            TxStatus.CONFIRMED -> { log(a, PHASE_COMMITTED, txHash = hash); progress(Phase.COMMITTED); true }
            TxStatus.REJECTED -> {
                log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node"); progress(Phase.FAILED)
                if (fresh.state == CARD_ISSUING) store.deleteIssuing(fresh.reserveId) else clearPending(fresh, activate = false)
                return@exclusive ActionOutcome.Failed("rejected by the node")
            }
            else -> return@exclusive ActionOutcome.StillConfirming(hash)
        }
        check(committed)
        val c = if (fresh.pendingCommitted) fresh else onCommitted(fresh, hash)
        writePending(tag, c, a, progress)
    } ?: busy()

    /** Apply the chain view of a just-confirmed pending transaction to the index. */
    private suspend fun onCommitted(card: PrepaidCardEntity, hash: String): PrepaidCardEntity {
        val pending = card.pendingUnits ?: 0
        val c = card.copy(pendingCommitted = true, balanceUnits = card.balanceUnits + pending,
            currentOutpoint = if (card.state == CARD_ISSUING) card.currentOutpoint else "$hash:0",
            state = if (card.state == CARD_ISSUING) CARD_ACTIVE else card.state, updatedAt = clock())
        store.updateCard(c)
        return c
    }

    /** On page open: re-poll every pending transaction once. Returns how many rows changed. */
    suspend fun recoverPending(): Int = exclusive {
        var changed = 0
        for (card in store.cardsWithPending()) {
            val hash = card.pendingTxHash!!
            val a = auditForPending(card)
            if (card.pendingCommitted) {
                if (card.pendingUnits == null) { clearPending(card, activate = true); log(a, PHASE_DONE, txHash = hash); changed++ }
                continue
            }
            when (chain.status(hash)) {
                TxStatus.CONFIRMED -> {
                    log(a, PHASE_COMMITTED, txHash = hash)
                    val c = onCommitted(card, hash)
                    if (c.pendingUnits == null) { clearPending(c, activate = true); log(a, PHASE_DONE, txHash = hash) }
                    changed++
                }
                TxStatus.REJECTED -> {
                    log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node")
                    if (card.state == CARD_ISSUING) store.deleteIssuing(card.reserveId) else clearPending(card, activate = false)
                    changed++
                }
                else -> {}
            }
        }
        changed
    } ?: 0
}
