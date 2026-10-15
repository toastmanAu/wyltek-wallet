package com.wyltek.wallet.prepaid.ui

import android.app.Application
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.prepaid.*
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.nfc.MifareTagPort
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.util.Log

enum class TapAction { ISSUE, LOAD, RESUME, ADOPT, REPAIR, REVOKE, REINSTATE, VOID, CASH_OUT, WIPE, ISSUE_VOUCHER, VOID_VOUCHER, WIPE_VOUCHER, RESUME_VOUCHER }

/** A confirmed step waiting for one specific card to be tapped. The card may leave the phone (address scan) in between. */
sealed class Armed {
    abstract val reserveId: String; abstract val uid: String
    data class CashOut(override val reserveId: String, override val uid: String, val address: String) : Armed()
    data class Wipe(override val reserveId: String, override val uid: String) : Armed()
}

object PrepaidUiReducer {
    /** What stays armed after an attempt: a missing card keeps it; a cash-out whose wipe missed the card becomes a wipe. */
    fun armAfter(armed: Armed, out: ActionOutcome): Armed? = when (out) {
        is ActionOutcome.NeedsTag -> armed
        is ActionOutcome.WipePending -> Armed.Wipe(armed.reserveId, armed.uid)
        is ActionOutcome.Failed -> armed.takeIf { out.reason == "busy" }    // another action held the lock; nothing ran
        else -> null
    }

    /** The arm after [ran] finished. Only the arm that ran may be replaced: if the user dismissed or re-armed
     *  meanwhile, a late result must not bring a hidden cash-out back. */
    fun settleArm(current: Armed?, ran: Armed, out: ActionOutcome): Armed? = if (current == ran) armAfter(ran, out) else current

    fun armedFor(armed: Armed?, uidHex: String): Boolean = armed != null && armed.uid.equals(uidHex, ignoreCase = true)

    fun tagGone(out: ActionOutcome): Boolean = out is ActionOutcome.AwaitingTag || out is ActionOutcome.NeedsTag ||
        out is ActionOutcome.WipePending || (out is ActionOutcome.Failed && out.reason.lowercase().let { "tag lost" in it || "could not read" in it })

    /** C4b: the "Accept card as-is" way out shows only after the Ruling-20 terminal refusal. */
    fun offersAcceptAsIs(outcome: ActionOutcome?, tap: TapClass?): Boolean =
        tap is TapClass.Resume && outcome is ActionOutcome.Failed && "used at a terminal" in outcome.reason

    /** Tag-requiring actions are hidden once the tag is gone; only Resume stays (the sheet then says "tap the card again"). */
    fun tapActions(tap: TapClass?, tagPresent: Boolean): List<TapAction> =
        if (tagPresent) tapActionsWithTag(tap) else if (tap is TapClass.Resume) listOf(TapAction.RESUME)
        else if (tap is TapClass.VoucherResume) listOf(TapAction.RESUME_VOUCHER) else emptyList()

    /** Stores a freshly scanned customer address in state; null leaves the state untouched. */
    fun consumeScannedAddress(state: PrepaidUiState, address: String?): PrepaidUiState =
        if (address == null) state else state.copy(scannedAddress = address)

    private fun tapActionsWithTag(tap: TapClass?): List<TapAction> = when (tap) {
        null -> emptyList()
        is TapClass.Refused -> if (tap.reason == TapClassifier.REASON_CLOSED) listOf(TapAction.WIPE) else emptyList()
        is TapClass.Blank -> listOf(TapAction.ISSUE, TapAction.ISSUE_VOUCHER)
        is TapClass.Resume -> listOf(TapAction.RESUME)
        is TapClass.Damaged -> listOf(TapAction.REPAIR, TapAction.VOID, TapAction.CASH_OUT)
        is TapClass.Bench -> listOf(TapAction.ADOPT)
        is TapClass.Voucher -> if (tap.voucher.state == VOUCHER_ACTIVE) listOf(TapAction.VOID_VOUCHER)
            else if (tap.voucher.state in setOf(VOUCHER_VOID, VOUCHER_USED, VOUCHER_CLOSED)) listOf(TapAction.WIPE_VOUCHER) else emptyList()
        is TapClass.VoucherResume -> listOf(TapAction.RESUME_VOUCHER)
        is TapClass.VoucherDamaged -> if (tap.voucher.state == VOUCHER_ACTIVE) listOf(TapAction.VOID_VOUCHER) else emptyList()
        is TapClass.Known -> when (tap.card.state) {
            CARD_ACTIVE -> listOf(TapAction.LOAD, TapAction.REVOKE, TapAction.CASH_OUT, TapAction.VOID)
            CARD_REVOKED -> listOf(TapAction.REINSTATE, TapAction.CASH_OUT, TapAction.VOID)
            else -> emptyList()
        }
    }
}

data class PrepaidUiState(
    val cards: List<PrepaidCardEntity> = emptyList(),
    val nfcAvailable: Boolean = false,
    val unavailableReason: String? = null,
    val tap: TapClass? = null,
    val tagPresent: Boolean = false,
    val phases: List<Pair<Phase, Long>> = emptyList(),
    val outcome: ActionOutcome? = null,
    val busy: Boolean = false,
    val merchantIdHex: String = "",
    val reserveTotalCkb: String = "",
    val badge: String = "DEMO",
    /** Customer address scanned for a cash-out; cleared on dismiss, cancel and use. */
    val scannedAddress: String? = null,
    /** Bumped on every tap result so previews re-run when the same card is tapped again. */
    val tapEpoch: Long = 0,
    /** One-off gateway warning (K_debit regenerated); cleared by [PrepaidViewModel.dismissWarning]. */
    val warning: String? = null,
    /** A confirmed step waiting for its card; cleared on dismiss. */
    val armed: Armed? = null,
)

/** The tag and classification the user was looking at when they clicked; actions use this, never later state. */
data class TapContext(val tag: TagPort, val tap: TapClass)

/** Issue-form cap on the product name (the service has none; the POS shows it on a 480-px line). */
const val VOUCHER_NAME_MAX = 40

class PrepaidViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = (application as WyltekWalletApp).agentGateway
    private val svc: PrepaidService? = gateway.prepaid
    /** The one VoucherService the gateway wired to [svc] (never construct another: it installs the settle hooks). */
    private val vsvc: VoucherService? = gateway.vouchers
    @Volatile private var ctx: TapContext? = null
    private val _ui = MutableStateFlow(PrepaidUiState(
        nfcAvailable = NfcAdapter.getDefaultAdapter(application) != null,
        unavailableReason = if (svc == null) gateway.prepaidUnavailableReason else null,
        merchantIdHex = svc?.merchantIdHex ?: "",
        warning = gateway.prepaidWarning))
    val ui: StateFlow<PrepaidUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { gateway.prepaidStore().cardsFlow().collect { cards ->
            val total = cards.filter { it.state == CARD_ACTIVE || it.state == CARD_REVOKED }.sumOf { it.balanceUnits.toLong() }
            _ui.update { it.copy(cards = cards, reserveTotalCkb = PrepaidAmount.formatUnits(total)) } } }
        viewModelScope.launch {
            try { svc?.recoverPending() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("PrepaidViewModel", "recoverPending failed", e) }
            try { vsvc?.recoverPending() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("PrepaidViewModel", "voucher recoverPending failed", e) }
        }
    }

    fun currentContext(): TapContext? = ctx

    fun dismissWarning() { gateway.dismissPrepaidWarning(); _ui.update { it.copy(warning = null) } }

    fun consumeScannedAddress(address: String?) { _ui.update { PrepaidUiReducer.consumeScannedAddress(it, address) } }
    fun clearScannedAddress() { _ui.update { it.copy(scannedAddress = null) } }

    fun onTag(raw: Tag) {
        val s = svc ?: return
        if (_ui.value.busy) return                       // a running action keeps its context
        if (!MifareTagPort.accepts(raw)) { _ui.update { it.copy(tap = TapClass.Refused("not an NTAG21x card"), tagPresent = false) }; return }
        val port = MifareTagPort(raw)
        val armed = _ui.value.armed
        if (armed != null) {
            if (PrepaidUiReducer.armedFor(armed, port.uidHex)) { _ui.update { it.copy(tagPresent = true) }; runArmed(port) }
            else _ui.update { it.copy(outcome = ActionOutcome.Failed("this is a different card; present the armed card")) }
            return
        }
        val pending = _ui.value.tap as? TapClass.Resume
        val pendingVoucher = _ui.value.tap as? TapClass.VoucherResume
        viewModelScope.launch {
            if (pending != null && pending.card.uid.equals(port.uidHex, true) && _ui.value.outcome is ActionOutcome.AwaitingTag) {
                val c = TapContext(port, pending); ctx = c
                _ui.update { it.copy(tagPresent = true) }
                run("resume") { s.resume(port, pending.card, ::phase) }; return@launch
            }
            val v = vsvc
            if (v != null && pendingVoucher != null && pendingVoucher.voucher.uid.equals(port.uidHex, true) && _ui.value.outcome is ActionOutcome.AwaitingTag) {
                ctx = TapContext(port, pendingVoucher)
                _ui.update { it.copy(tagPresent = true) }
                run("resume voucher") { v.resume(port, pendingVoucher.voucher, ::phase) }; return@launch
            }
            // Voucher classifier first: a voucher tag must never come back Blank and be offered "Issue card".
            val cls = try { v?.classify(port) ?: s.classify(port) } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ctx = null
                _ui.update { it.copy(tap = TapClass.Refused("could not read the card: ${e.message}"), tagPresent = false, tapEpoch = it.tapEpoch + 1) }
                return@launch
            }
            ctx = TapContext(port, cls)
            _ui.update { it.copy(tap = cls, tagPresent = true, phases = emptyList(), outcome = null, tapEpoch = it.tapEpoch + 1) }
        }
    }

    fun dismissTap() { ctx = null; _ui.update { it.copy(tap = null, tagPresent = false, phases = emptyList(), outcome = null, scannedAddress = null, armed = null) } }

    /** Drop an armed step without running it (nothing on chain for a cash-out; a wipe leaves the closed card's record). */
    fun disarm() { _ui.update { it.copy(armed = null, outcome = null) } }

    private fun phase(p: Phase) { _ui.update { it.copy(phases = it.phases + (p to System.currentTimeMillis())) } }

    private suspend fun run(what: String, block: suspend () -> ActionOutcome): ActionOutcome {
        _ui.update { it.copy(busy = true, outcome = null) }
        val out = try { block() } catch (e: CancellationException) { _ui.update { it.copy(busy = false) }; throw e }
        catch (e: Exception) { ActionOutcome.Failed("$what failed: ${e.message}") }
        _ui.update { it.copy(busy = false, outcome = out, tagPresent = it.tagPresent && !PrepaidUiReducer.tagGone(out)) }
        return out
    }

    private val noTap = ActionOutcome.Failed("tap the card first")
    private val badAmount = ActionOutcome.Failed("enter an amount in CKB, up to three decimals")

    fun issue(c: TapContext?, amountText: String, label: String) = act(c) { s, t ->
        val u = PrepaidAmount.parseCkbToUnits(amountText) ?: return@act badAmount; s.issue(t.tag, u, label, ::phase) }
    fun load(c: TapContext?, amountText: String) = act(c) { s, t ->
        val card = (t.tap as? TapClass.Known)?.card ?: return@act ActionOutcome.Failed("no card")
        val u = PrepaidAmount.parseCkbToUnits(amountText) ?: return@act badAmount; s.load(t.tag, card, u, ::phase) }
    fun resume(c: TapContext?) = act(c) { s, t -> val card = (t.tap as? TapClass.Resume)?.card ?: return@act ActionOutcome.Failed("no card"); s.resume(t.tag, card, ::phase) }
    fun adopt(c: TapContext?) = act(c) { s, t -> val r = (t.tap as? TapClass.Bench)?.record ?: return@act ActionOutcome.Failed("no record"); s.adopt(t.tag, r, ::phase) }
    fun repair(c: TapContext?) = act(c) { s, t -> val card = (t.tap as? TapClass.Damaged)?.card ?: return@act ActionOutcome.Failed("no card"); s.repair(t.tag, card, ::phase) }
    /** C4b: close a committed credit the tag can no longer take (a terminal wrote after it was intended). */
    fun acceptTagAsIs(c: TapContext?) = act(c) { s, t -> val card = (t.tap as? TapClass.Resume)?.card ?: return@act ActionOutcome.Failed("no card"); s.acceptTagAsIs(t.tag, card, ::phase) }
    /** Arms the cash-out; it runs on the next tap of [card] (now, if that card's tag is still the one in hand). */
    fun cashOut(card: PrepaidCardEntity, address: String) {
        _ui.update { it.copy(armed = Armed.CashOut(card.reserveId, card.uid, address), outcome = null, phases = emptyList()) }
        ctx?.tag?.takeIf { _ui.value.tagPresent && it.uidHex.equals(card.uid, true) }?.let(::runArmed)
    }

    /** Wipe the tag of a closed card that still carries its record (it classifies as Refused(closed)). */
    fun wipeClosed(c: TapContext?) {
        val tag = c?.tag ?: return
        viewModelScope.launch {
            val card = gateway.prepaidStore().cardByUid(tag.uidHex)?.takeIf { it.state == CARD_CLOSED }
            if (card == null) { _ui.update { it.copy(outcome = ActionOutcome.Failed("no closed card with this UID in the index")) }; return@launch }
            _ui.update { it.copy(armed = Armed.Wipe(card.reserveId, card.uid), outcome = null) }
            runArmed(tag)
        }
    }

    private fun runArmed(tag: TagPort) {
        val s = svc ?: return
        val armed = _ui.value.armed ?: return
        _ui.update { it.copy(busy = true) }                  // synchronously: a second tap must not launch it twice
        viewModelScope.launch { runArmed(s, armed, tag) }
    }

    private suspend fun runArmed(s: PrepaidService, armed: Armed, tag: TagPort) {
        val card = gateway.prepaidStore().card(armed.reserveId)
        if (card == null) { _ui.update { it.copy(busy = false, armed = null, outcome = ActionOutcome.Failed("card not in the index")) }; return }
        val out = run(if (armed is Armed.CashOut) "cash-out" else "wipe") {
            when (armed) {
                is Armed.CashOut -> s.cashOut(tag, card, armed.address, ::phase)
                is Armed.Wipe -> s.wipeClosedTag(tag, card)
            }
        }
        _ui.update { it.copy(armed = PrepaidUiReducer.settleArm(it.armed, armed, out)) }
    }
    fun revoke(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("revoke") { s.revoke(card) } } }
    fun reinstate(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("reinstate") { s.reinstate(card) } } }
    /** [c] must be captured at click time (before any biometric prompt); the default is for callers with no tap sheet. */
    fun voidCard(card: PrepaidCardEntity, c: TapContext? = ctx) = viewModelScope.launch {
        svc?.let { s -> run("void") { s.void(card, c?.tag?.takeIf { it.uidHex.equals(card.uid, true) }, ::phase) } } }
    suspend fun preview(card: PrepaidCardEntity): CashOutPreview? = svc?.inspectForRetire(ctx?.tag, card)

    fun issueVoucher(c: TapContext?, code: String, name: String, priceText: String, currency: String, countText: String) = act(c) { _, t ->
        val v = vsvc ?: return@act ActionOutcome.Failed("vouchers unavailable")
        val cur = currency.trim().uppercase()
        if (name.trim().length > VOUCHER_NAME_MAX) return@act ActionOutcome.Failed("product name is limited to $VOUCHER_NAME_MAX characters")
        val price = VoucherMoney.parse(priceText, cur) ?: return@act ActionOutcome.Failed("enter the price, e.g. 4.50")
        val count = countText.trim().toIntOrNull() ?: return@act ActionOutcome.Failed("enter how many uses (1-255)")
        v.issue(t.tag, VoucherForm(code, name, price, cur, count), ::phase)
    }
    fun resumeVoucher(c: TapContext?) = act(c) { _, t ->
        val voucher = (t.tap as? TapClass.VoucherResume)?.voucher ?: return@act ActionOutcome.Failed("no voucher")
        val v = vsvc ?: return@act ActionOutcome.Failed("vouchers unavailable")
        v.resume(t.tag, voucher, ::phase)
    }
    fun wipeVoucher(c: TapContext?) = act(c) { _, t ->
        val voucher = (t.tap as? TapClass.Voucher)?.voucher ?: return@act ActionOutcome.Failed("no voucher")
        val v = vsvc ?: return@act ActionOutcome.Failed("vouchers unavailable")
        v.wipe(t.tag, voucher)
    }
    /** [c] captured at click time, before the biometric prompt. */
    fun voidVoucher(v: VoucherEntity, c: TapContext? = ctx) = viewModelScope.launch {
        vsvc?.let { s -> run("void voucher") { s.void(v, c?.tag?.takeIf { it.uidHex.equals(v.uid, true) }, ::phase) } } }
    fun vouchersFlow(): Flow<List<VoucherEntity>> = gateway.voucherStore().vouchersFlow()
    suspend fun voucher(id: String) = gateway.voucherStore().voucher(id)
    fun tappedVoucher(): VoucherEntity? = when (val t = ui.value.tap) { is TapClass.Voucher -> t.voucher; is TapClass.VoucherDamaged -> t.voucher; else -> null }

    fun eventsFlow(): Flow<List<PrepaidEventEntity>> = gateway.prepaidStore().eventsFlow()
    suspend fun eventsFor(reserveId: String) = gateway.prepaidStore().eventsForCard(reserveId)
    suspend fun card(reserveId: String) = gateway.prepaidStore().card(reserveId)
    suspend fun liveCell(card: PrepaidCardEntity): Utxo? = gateway.prepaidLiveCell(card.currentOutpoint)
    suspend fun allEventsCsv(): String = AuditCsv.render(gateway.prepaidStore().allEvents())
    fun tagRecordFor(card: PrepaidCardEntity): TapClass.Known? = (ui.value.tap as? TapClass.Known)?.takeIf { it.card.reserveId == card.reserveId }

    private fun cardOf(t: TapClass?): PrepaidCardEntity? = when (t) { is TapClass.Known -> t.card; is TapClass.Damaged -> t.card; is TapClass.Resume -> t.card; else -> null }
    fun tappedCard(): PrepaidCardEntity? = cardOf(ui.value.tap)

    private fun act(c: TapContext?, block: suspend (PrepaidService, TapContext) -> ActionOutcome) = viewModelScope.launch {
        val s = svc ?: return@launch
        if (c == null) { _ui.update { it.copy(outcome = noTap) }; return@launch }
        run("action") { block(s, c) }
    }
}
