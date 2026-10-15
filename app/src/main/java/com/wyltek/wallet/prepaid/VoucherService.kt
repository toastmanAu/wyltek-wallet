package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.CancellationException

data class VoucherForm(val productCode: String, val productName: String, val issuePriceMinor: Long, val currency: String, val count: Int)

/**
 * Item vouchers (vouchers spec §3, §8). Shares PrepaidService's lock, chain helpers and audit log, so a voucher action
 * and a card action never build transactions at the same time. Chain first, tag second, as for cards.
 */
class VoucherService(private val core: PrepaidService, private val vouchers: VoucherStore) : VoucherHooks {
    companion object {
        val VOUCHER_CELL_SHANNONS: ULong = 6_900_000_000uL
        const val VOUCHER_CELL_DATA = "0x4242563100000000"
        private const val MAX_PRICE_MINOR = 4_294_967_295L
    }

    init { core.voucherHooks = this }

    private fun busy() = ActionOutcome.Failed("busy")
    private fun cleared(v: VoucherEntity) = v.copy(pendingKind = null, pendingTxHash = null, pendingActionId = null, pendingCommitted = false,
        pendingSince = null, updatedAt = core.clock())
    private fun auditOf(v: VoucherEntity, kind: String) = PrepaidService.Audit(v.pendingActionId ?: core.newActionId(), kind, v.uid, v.voucherId,
        v.countLeft.toLong(), v.countLeft, v.tagCounter)

    // ------------------------------------------------------------ classify
    /** Voucher classifier first; the card classifier only when the tag is not a voucher, so a voucher tag is never Blank. */
    suspend fun classify(tag: TagPort): TapClass {
        val raw = try { tag.readSlots() } catch (e: CancellationException) { throw e } catch (e: Exception) { return TapClass.Refused("tag lost while reading") }
        val vslot = core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, raw)
        val byId = vslot.record?.let { vouchers.voucher(it.voucherIdHex) }
        return VoucherClassifier.classify(tag.uidHex, vslot, vouchers.voucherByUid(tag.uidHex), byId, core.merchantIdHex)
            ?: core.classifyRaw(tag.uidHex, raw)
    }

    // ------------------------------------------------------------ issue
    private fun formError(f: VoucherForm): String? = when {
        f.count !in 1..255 -> "uses must be 1 to 255"
        f.issuePriceMinor !in 0..MAX_PRICE_MINOR -> "price out of range"
        !f.currency.matches(Regex("[A-Z]{3}")) -> "currency must be three letters, e.g. AUD"
        f.productName.isBlank() -> "enter a product name"
        else -> null
    }

    suspend fun issue(tag: TagPort, form: VoucherForm, progress: (Phase) -> Unit = {}): ActionOutcome = core.exclusive {
        formError(form)?.let { return@exclusive ActionOutcome.Failed(it) }
        val code = try { core.protocol.normaliseProductCode(form.productCode) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed("product code must be 1-31 printable characters") }
        core.store.cardByUid(tag.uidHex)?.takeIf { it.state != CARD_CLOSED }?.let { return@exclusive ActionOutcome.Failed("this tag is a prepaid card") }
        vouchers.voucherByUid(tag.uidHex)?.takeIf { it.state in VOUCHER_LIVE }?.let { return@exclusive ActionOutcome.Failed("this tag already holds a voucher") }
        val lock = core.signer.lock
        val planned = try {
            PrepaidTxPlanner.plan(emptyList(), core.chain.cellsByLock(lock), core.excludedOutpoints(), listOf(PrepaidTxPlanner.output(VOUCHER_CELL_SHANNONS, lock, VOUCHER_CELL_DATA)), lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = core.build(planned)
        val voucherId = core.protocol.voucherId(built.txHashHex, 0u)
        val a = PrepaidService.Audit(core.newActionId(), KIND_VOUCHER_ISSUE, tag.uidHex, voucherId, form.count.toLong(), form.count, 0)
        val now = core.clock()
        vouchers.insertVoucher(VoucherEntity(voucherId = voucherId, uid = tag.uidHex, productCode = code, productName = form.productName.trim(),
            productHash = core.protocol.productHash(code), issuePriceMinor = form.issuePriceMinor, currency = form.currency, countIssued = form.count,
            countLeft = form.count, outpoint = "${built.txHashHex}:0", state = VOUCHER_ISSUING, pendingKind = KIND_VOUCHER_ISSUE,
            pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingSince = now, issuedAt = now, updatedAt = now))
        core.log(a, PHASE_REQUESTED, detail = "${form.count} × $code ${form.productName.trim()}; issue price ${form.issuePriceMinor} ${form.currency} (minor units)"); progress(Phase.REQUESTED)
        when (val r = core.broadcastAndAwait(a, planned, built, progress)) {
            is PrepaidService.TxResult.Refused -> { vouchers.deleteIssuing(voucherId); ActionOutcome.Failed(r.reason) }
            is PrepaidService.TxResult.Rejected -> { vouchers.deleteIssuing(voucherId); ActionOutcome.Failed("rejected by the node") }
            is PrepaidService.TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is PrepaidService.TxResult.Committed -> {
                val v = vouchers.voucher(voucherId)!!.copy(state = VOUCHER_ACTIVE, pendingCommitted = true, updatedAt = core.clock())
                vouchers.updateVoucher(v)
                writeTag(tag, v, a, progress)
            }
        }
    } ?: busy()

    /** Writes the issue record (counter 0) to slot A, zeroes B, reads back. Idempotent: an already-landed record finalises. */
    internal suspend fun writeTag(tag: TagPort, v: VoucherEntity, a: PrepaidService.Audit, progress: (Phase) -> Unit): ActionOutcome {
        if (!tag.uidHex.equals(v.uid, true)) return ActionOutcome.Failed("this is a different tag; present the voucher ${v.productName}")
        val before = try { core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, tag.readSlots()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return ActionOutcome.AwaitingTag(v.voucherId) }
        if (before.record?.voucherIdHex.equals(v.voucherId, true) && before.record?.counter == 0u) return finaliseIssue(v, a, progress, "already on tag")
        val raw = core.protocol.voucherSeal(core.kHex, tag.uidHex, VoucherRecordInfo(0u, v.voucherId, v.productHash, v.issuePriceMinor.toUInt(),
            v.currency, v.countIssued.toUByte(), 0u, core.merchantIdHex))
        try { tag.writeSlot(SlotPickFfi.A, raw); tag.zeroSlot(SlotPickFfi.B) }
        catch (e: CancellationException) { throw e } catch (e: Exception) {
            core.log(a, PHASE_FAILED, txHash = v.pendingTxHash, detail = "write interrupted: ${e.message}"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(v.voucherId)
        }
        val back = try { core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, tag.readSlots()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                core.log(a, PHASE_FAILED, txHash = v.pendingTxHash, detail = "readback interrupted"); progress(Phase.FAILED)
                return ActionOutcome.AwaitingTag(v.voucherId)
            }
        if (!(back.record?.voucherIdHex.equals(v.voucherId, true) && back.record?.counter == 0u)) {
            core.log(a, PHASE_FAILED, txHash = v.pendingTxHash, detail = "readback mismatch"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(v.voucherId)
        }
        return finaliseIssue(v, a, progress, "")
    }

    private suspend fun finaliseIssue(v: VoucherEntity, a: PrepaidService.Audit, progress: (Phase) -> Unit, detail: String): ActionOutcome {
        val hash = v.pendingTxHash
        vouchers.updateVoucher(cleared(v).copy(state = VOUCHER_ACTIVE))
        core.log(a, PHASE_WRITTEN, txHash = hash, detail = detail); progress(Phase.WRITTEN)
        core.log(a, PHASE_DONE, txHash = hash, detail = detail); progress(Phase.DONE)
        return ActionOutcome.Done
    }

    // ------------------------------------------------------------ resume / recover
    /** One status check of an unconfirmed issue. True = now committed (row updated), false = gone (row deleted), null = still waiting. */
    private suspend fun checkIssue(v: VoucherEntity, a: PrepaidService.Audit): Boolean? {
        val hash = v.pendingTxHash!!
        val st = core.statusOrNull(hash)
        return when {
            st == TxStatus.CONFIRMED -> { vouchers.updateVoucher(v.copy(state = VOUCHER_ACTIVE, pendingCommitted = true, updatedAt = core.clock()))
                core.log(a, PHASE_COMMITTED, txHash = hash); true }
            st == TxStatus.REJECTED -> { core.log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node"); vouchers.deleteIssuing(v.voucherId); false }
            st == TxStatus.UNKNOWN && v.pendingSince != null && core.clock() - v.pendingSince >= PrepaidService.PENDING_UNKNOWN_GIVE_UP_MS -> {
                core.log(a, PHASE_FAILED, txHash = hash, detail = "transaction unknown to the node for 30 min; given up"); vouchers.deleteIssuing(v.voucherId); false }
            else -> null
        }
    }

    suspend fun resume(tag: TagPort, voucher: VoucherEntity, progress: (Phase) -> Unit = {}): ActionOutcome = core.exclusive {
        val v = vouchers.voucher(voucher.voucherId) ?: return@exclusive ActionOutcome.Failed("voucher not in the index")
        val hash = v.pendingTxHash ?: return@exclusive ActionOutcome.Done
        if (v.pendingKind != KIND_VOUCHER_ISSUE) return@exclusive resumeSpend(v, tag, progress)
        val a = auditOf(v, KIND_VOUCHER_ISSUE)
        if (!v.pendingCommitted) when (checkIssue(v, a)) {
            null -> return@exclusive ActionOutcome.StillConfirming(hash)
            false -> return@exclusive ActionOutcome.Failed("the issue did not commit")
            true -> progress(Phase.COMMITTED)
        }
        writeTag(tag, vouchers.voucher(v.voucherId)!!, a, progress)
    } ?: busy()

    /** One status check of an unconfirmed void/close. Committed: the row ends VOID/CLOSED (and a void wipes [tag] if it
     *  carries this voucher); rejected or given up: the pending marker is cleared and the voucher stays as it was. */
    internal suspend fun resumeSpend(v: VoucherEntity, tag: TagPort?, progress: (Phase) -> Unit): ActionOutcome {
        val hash = v.pendingTxHash!!
        val a = auditOf(v, v.pendingKind!!)
        val st = core.statusOrNull(hash)
        return when {
            st == TxStatus.CONFIRMED -> { core.log(a, PHASE_COMMITTED, txHash = hash); progress(Phase.COMMITTED)
                finishSpend(v, a, endStateOf(v.pendingKind), tag, hash, progress) }
            st == TxStatus.REJECTED -> { core.log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node"); vouchers.updateVoucher(cleared(v))
                ActionOutcome.Failed("rejected by the node") }
            st == TxStatus.UNKNOWN && v.pendingSince != null && core.clock() - v.pendingSince >= PrepaidService.PENDING_UNKNOWN_GIVE_UP_MS -> {
                core.log(a, PHASE_FAILED, txHash = hash, detail = "transaction unknown to the node for 30 min; given up"); vouchers.updateVoucher(cleared(v))
                ActionOutcome.Failed("transaction unknown to the node; given up") }
            else -> ActionOutcome.StillConfirming(hash)
        }
    }

    /** On page open: re-poll every unconfirmed voucher transaction once. Returns how many rows changed. */
    suspend fun recoverPending(): Int = core.exclusive {
        var changed = 0
        for (v in vouchers.vouchersWithPending()) {
            if (v.pendingKind == KIND_VOUCHER_ISSUE) { if (!v.pendingCommitted && checkIssue(v, auditOf(v, KIND_VOUCHER_ISSUE)) != null) changed++ }
            else if (resumeSpend(v, null, {}) !is ActionOutcome.StillConfirming) changed++
        }
        changed + closeUsedLocked()
    } ?: 0

    // ------------------------------------------------------------ void / close / wipe
    /** Spend [v]'s cell back to the merchant, ending in [endState]. A cell the indexer cannot see is a FAILURE, never a
     *  close: an RPC outage looks the same (Review Focus 4). The pending marker is written before broadcast, as for card
     *  retires; a refusal or rejection puts the row back exactly as it was. */
    private suspend fun spendCell(v: VoucherEntity, kind: String, endState: String, tag: TagPort?, progress: (Phase) -> Unit): ActionOutcome {
        // Pre-marker reads: a query that threw is "no answer" (house rule, statusOrNull), so Failed with no row change.
        val (planned, built) = try {
            val cell = core.chain.cellAt(v.outpoint) ?: return ActionOutcome.Failed("voucher cell not found on chain; check the RPC and try again")
            val lock = core.signer.lock
            val p = PrepaidTxPlanner.plan(listOf(cell), core.chain.cellsByLock(lock), core.excludedOutpoints() - normalizeOutpoint(v.outpoint), emptyList(), lock)
            p to core.build(p)
        } catch (e: CancellationException) { throw e }
        catch (e: InsufficientFunds) { return ActionOutcome.Failed(e.message!!) }
        catch (e: Exception) { return ActionOutcome.Failed("could not read the voucher cell (${e.message}); check the RPC and try again") }
        val a = PrepaidService.Audit(core.newActionId(), kind, v.uid, v.voucherId, -v.countLeft.toLong(), v.countLeft, v.tagCounter)
        core.log(a, PHASE_REQUESTED, detail = "${v.countLeft} of ${v.countIssued} uses left"); progress(Phase.REQUESTED)
        vouchers.updateVoucher(v.copy(pendingKind = kind, pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingCommitted = false,
            pendingSince = core.clock(), updatedAt = core.clock()))
        return when (val r = core.broadcastAndAwait(a, planned, built, progress)) {
            is PrepaidService.TxResult.Refused -> { vouchers.updateVoucher(v.copy(updatedAt = core.clock())); ActionOutcome.Failed(r.reason) }
            is PrepaidService.TxResult.Rejected -> { vouchers.updateVoucher(v.copy(updatedAt = core.clock())); ActionOutcome.Failed("rejected by the node") }
            is PrepaidService.TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is PrepaidService.TxResult.Committed -> finishSpend(vouchers.voucher(v.voucherId)!!, a, endState, tag, r.txHash, progress)
        }
    }

    private fun endStateOf(kind: String?) = if (kind == KIND_VOUCHER_VOID) VOUCHER_VOID else VOUCHER_CLOSED

    private suspend fun finishSpend(v: VoucherEntity, a: PrepaidService.Audit, endState: String, tag: TagPort?, hash: String, progress: (Phase) -> Unit): ActionOutcome {
        vouchers.updateVoucher(cleared(v).copy(state = endState, countLeft = 0))
        val wiped = if (endState == VOUCHER_VOID) wipeTag(tag, v) else "tag left as is"
        core.log(a, PHASE_DONE, balanceAfter = 0, txHash = hash, detail = wiped); progress(Phase.DONE)
        return ActionOutcome.Done
    }

    /** Zeroes both slots only when [tag] is [v]'s tag AND its current record is this voucher's, under our merchant id.
     *  Anything else (another voucher, another merchant, unreadable, ambiguous) is left alone. Returns the audit detail. */
    private suspend fun wipeTag(tag: TagPort?, v: VoucherEntity): String {
        if (tag == null || !tag.uidHex.equals(v.uid, true)) return "tag absent"
        val slot = try { core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, tag.readSlots()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return "tag wipe interrupted: ${e.message}" }
        if (slot.pick == SlotPickFfi.NONE && slot.reason == SlotReason.NO_VALID_SLOT) return "tag already blank"
        val rec = slot.record
        if (rec == null || !rec.voucherIdHex.equals(v.voucherId, true) || !rec.merchantIdHex.equals(core.merchantIdHex, true))
            return "tag does not carry this voucher; not wiped"
        return try { tag.zeroSlot(SlotPickFfi.A); tag.zeroSlot(SlotPickFfi.B); "tag wiped" }
            catch (e: CancellationException) { throw e } catch (e: Exception) { "tag wipe interrupted: ${e.message}" }
    }

    /** Void an active voucher: its cell goes back to the merchant and, if [tag] is its tag, the record is wiped. */
    suspend fun void(voucher: VoucherEntity, tag: TagPort?, progress: (Phase) -> Unit = {}): ActionOutcome = core.exclusive {
        val v = vouchers.voucher(voucher.voucherId) ?: return@exclusive ActionOutcome.Failed("voucher not in the index")
        if (v.state != VOUCHER_ACTIVE) return@exclusive ActionOutcome.Failed("voucher is ${v.state}")
        if (v.pendingTxHash != null && !(v.pendingKind == KIND_VOUCHER_ISSUE && v.pendingCommitted))
            return@exclusive ActionOutcome.Failed("finish the pending action first (tap the voucher)")
        spendCell(v, KIND_VOUCHER_VOID, VOUCHER_VOID, tag?.takeIf { it.uidHex.equals(v.uid, true) }, progress)
    } ?: busy()

    /** Spend the cell of every used-up voucher (state USED, nothing pending). Never touches an ACTIVE voucher.
     *  Called after each settlement (inside its lock) and from [recoverPending]. Returns how many closed now. */
    internal suspend fun closeUsedLocked(): Int {
        var closed = 0
        for (v in vouchers.allVouchers().filter { it.state == VOUCHER_USED && it.countLeft == 0 && it.pendingTxHash == null }) {
            // One voucher's failure never stops the rest, and never escapes into a settlement holding the lock.
            val out = try { spendCell(v, KIND_VOUCHER_CLOSE, VOUCHER_CLOSED, null, {}) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { ActionOutcome.Failed("close failed: ${e.message}") }
            if (out == ActionOutcome.Done) closed++
        }
        return closed
    }
    suspend fun closeUsed(): Int = core.exclusive { closeUsedLocked() } ?: 0

    /** Wipe a void, used or closed voucher's record from its tag. Only this voucher's own record is wiped. */
    suspend fun wipe(tag: TagPort, voucher: VoucherEntity): ActionOutcome = core.exclusive {
        val v = vouchers.voucher(voucher.voucherId) ?: return@exclusive ActionOutcome.Failed("voucher not in the index")
        if (v.state !in setOf(VOUCHER_VOID, VOUCHER_USED, VOUCHER_CLOSED)) return@exclusive ActionOutcome.Failed("voucher is ${v.state}; void it first")
        if (!tag.uidHex.equals(v.uid, true)) return@exclusive ActionOutcome.Failed("this is a different tag")
        val slot = try { core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, tag.readSlots()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return@exclusive ActionOutcome.NeedsTag(v.voucherId) }
        if (slot.pick == SlotPickFfi.NONE && slot.reason == SlotReason.NO_VALID_SLOT) return@exclusive ActionOutcome.Done
        val rec = slot.record
        if (rec == null || !rec.voucherIdHex.equals(v.voucherId, true) || !rec.merchantIdHex.equals(core.merchantIdHex, true))
            return@exclusive ActionOutcome.Failed("the tag does not carry this voucher; not wiped")
        val a = PrepaidService.Audit(core.newActionId(), KIND_REPAIR, v.uid, v.voucherId, 0, 0, v.tagCounter)
        core.log(a, PHASE_REQUESTED, detail = "wipe ${v.state} voucher")
        val wiped = wipeTag(tag, v)
        if (wiped != "tag wiped") { core.log(a, PHASE_FAILED, detail = wiped); return@exclusive ActionOutcome.NeedsTag(v.voucherId) }
        core.log(a, PHASE_DONE, detail = wiped)
        ActionOutcome.Done
    } ?: busy()

    // ------------------------------------------------------------ VoucherHooks (settlement)
    override suspend fun uidHoldsVoucher(uidHex: String) = vouchers.voucherByUid(uidHex)?.state in VOUCHER_LIVE
    /** Every voucher the verifier may meet, issuing ones excepted. USED and CLOSED go in too (void=false, with their
     *  settled entries) so an identical resend of a last-use redeem still answers `duplicate`; only VOID is void. */
    override suspend fun voucherStates(): List<VoucherStateFfi> = vouchers.allVouchers().filter { it.state != VOUCHER_ISSUING }.map { v ->
        VoucherStateFfi(v.voucherId, v.countLeft.coerceIn(0, 255).toUByte(), v.state == VOUCHER_VOID,
            vouchers.seenForVoucher(v.voucherId).mapNotNull { s -> s.counter?.let { SettledRedeemFfi(it.toUInt(), s.entryHash) } })
    }

    /** Finalises this batch's redeem (0x03) and override (0x04) verdicts. An entry stays `accepted` only once its record
     *  is written: seen entry + count (redeems, one transaction) and audit row. A failed write answers `retry` so the
     *  terminal keeps the entry; an entry already seen (earlier batch or earlier in this one) is `duplicate`, with no
     *  second count-down and no second audit row (Review Focus 5). */
    override suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String): List<String> {
        val out = names.toMutableList()
        // Only entries the verifier (and the card-side mapping before us) left `accepted` are acted on.
        fun accepted(i: UInt) = out.getOrNull(i.toInt()) == "accepted"
        settleRedeems(res.redeems.filter { accepted(it.index) }, out, terminalLabel)
        for (o in res.overrides) if (accepted(o.index)) out[o.index.toInt()] = settleOverride(o, terminalLabel)
        return out
    }

    private suspend fun settleRedeems(redeems: List<RedeemFfi>, out: MutableList<String>, terminalLabel: String) {
        if (redeems.isEmpty()) return
        fun mark(rs: List<RedeemFfi>, name: String) = rs.forEach { out[it.index.toInt()] = name }
        val now = core.clock()
        val (fresh, updated) = try {
            val hashes = mutableSetOf<String>(); val fresh = mutableListOf<RedeemFfi>()
            for (r in redeems) if (!hashes.add(r.entryHashHex.lowercase()) || vouchers.seen(r.entryHashHex.lowercase()) != null) mark(listOf(r), "duplicate") else fresh += r
            val updated = fresh.groupBy { it.voucherIdHex.lowercase() }.mapNotNull { (id, rs) ->
                val v = vouchers.voucher(id)
                if (v == null) { mark(rs, "retry"); null } else v to rs.sortedBy { it.counter } }
            fresh to updated
        } catch (e: CancellationException) { throw e } catch (e: Exception) { mark(redeems, "retry"); return }
        val rows = updated.map { (v, rs) ->
            val left = maxOf(0, v.countLeft - rs.size)
            v.copy(countLeft = left, state = if (left == 0 && v.state == VOUCHER_ACTIVE) VOUCHER_USED else v.state,
                tagCounter = maxOf(v.tagCounter, rs.maxOf { it.counter.toLong() }), updatedAt = now)
        }
        val seen = updated.flatMap { (v, rs) -> rs.map { JournalSeenEntity(it.entryHashHex.lowercase(), KIND_VOUCHER_REDEEM, v.voucherId, it.counter.toLong(), it.terminalId.toInt(), now) } }
        try { vouchers.applyRedeems(rows, seen) } catch (e: CancellationException) { throw e } catch (e: Exception) { mark(fresh, "retry"); return }
        for ((v, rs) in updated) rs.forEachIndexed { i, r ->
            val before = v.countLeft - i
            try {
                core.log(PrepaidService.Audit(core.newActionId(), KIND_VOUCHER_REDEEM, v.uid, v.voucherId, 1, before, r.counter.toLong() - 1), PHASE_DONE,
                    balanceAfter = maxOf(0, before - 1), counterAfter = r.counter.toLong(), detail = "$terminalLabel; terminal ${r.terminalId}; counter ${r.counter}")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mark(listOf(r), "retry") }   // counted; a resend answers duplicate
        }
    }

    /** The seen marker is written before the audit row: a crash between them can lose that row, never count it twice. */
    private suspend fun settleOverride(o: OverrideFfi, terminalLabel: String): String = try {
        val hash = o.entryHashHex.lowercase()
        if (vouchers.seen(hash) != null) "duplicate" else {
            vouchers.insertSeen(JournalSeenEntity(hash, KIND_PRICE_OVERRIDE, null, null, o.terminalId.toInt(), core.clock()))
            core.log(PrepaidService.Audit(core.newActionId(), KIND_PRICE_OVERRIDE, "", null, o.newPrice.toLong(), o.oldPrice, 0), PHASE_DONE,
                balanceAfter = o.newPrice, detail = "terminal ${o.terminalId}; product ${o.productHashHex}; ${o.oldPrice} -> ${o.newPrice} ${o.currency} (minor units); staff key ${o.keyIdHex}; $terminalLabel")
            "accepted"
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { "retry" }

    /** Called by PrepaidService.launchAfterSettle inside `exclusive`: closeUsedLocked never re-acquires the lock and catches per voucher. */
    override suspend fun afterSettle() { closeUsedLocked() }
}

/** The one place a [PrepaidService] gets its [VoucherService]: the hooks are installed before the service is handed out. */
internal fun wireVoucherService(core: PrepaidService, vouchers: VoucherStore): VoucherService =
    VoucherService(core, vouchers).also { check(core.voucherHooks === it) { "voucher hooks not installed" } }
