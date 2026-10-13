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
import kotlinx.coroutines.launch

enum class TapAction { ISSUE, LOAD, RESUME, ADOPT, REPAIR, REVOKE, REINSTATE, VOID, CASH_OUT }

object PrepaidUiReducer {
    fun tapActions(tap: TapClass?, tagPresent: Boolean): List<TapAction> = when (tap) {
        null, is TapClass.Refused -> emptyList()
        is TapClass.Blank -> listOf(TapAction.ISSUE)
        is TapClass.Resume -> listOf(TapAction.RESUME)
        is TapClass.Damaged -> listOf(TapAction.REPAIR, TapAction.VOID, TapAction.CASH_OUT)
        is TapClass.Bench -> listOf(TapAction.ADOPT)
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
)

class PrepaidViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = (application as WyltekWalletApp).agentGateway
    private val svc: PrepaidService? = gateway.prepaid
    private var tag: TagPort? = null
    private val _ui = MutableStateFlow(PrepaidUiState(
        nfcAvailable = NfcAdapter.getDefaultAdapter(application) != null,
        unavailableReason = if (svc == null) gateway.prepaidUnavailableReason else null,
        merchantIdHex = svc?.merchantIdHex ?: ""))
    val ui: StateFlow<PrepaidUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { gateway.prepaidStore().cardsFlow().collect { cards ->
            val total = cards.filter { it.state == CARD_ACTIVE || it.state == CARD_REVOKED }.sumOf { it.balanceUnits.toLong() }
            _ui.update { it.copy(cards = cards, reserveTotalCkb = PrepaidAmount.formatUnits(total)) } } }
        viewModelScope.launch { svc?.recoverPending() }
    }

    fun onTag(raw: Tag) {
        val s = svc ?: return
        if (!MifareTagPort.accepts(raw)) { _ui.update { it.copy(tap = TapClass.Refused("not an NTAG21x card"), tagPresent = false) }; return }
        val port = MifareTagPort(raw); tag = port
        val pending = _ui.value.tap as? TapClass.Resume
        viewModelScope.launch {
            if (pending != null && pending.card.uid.equals(port.uidHex, true) && _ui.value.outcome is ActionOutcome.AwaitingTag) { run("resume") { s.resume(port, pending.card, ::phase) }; return@launch }
            val cls = s.classify(port)
            _ui.update { it.copy(tap = cls, tagPresent = true, phases = emptyList(), outcome = null) }
        }
    }

    fun dismissTap() { tag = null; _ui.update { it.copy(tap = null, tagPresent = false, phases = emptyList(), outcome = null) } }

    private fun phase(p: Phase) { _ui.update { it.copy(phases = it.phases + (p to System.currentTimeMillis())) } }

    private suspend fun run(what: String, block: suspend () -> ActionOutcome) {
        _ui.update { it.copy(busy = true, outcome = null) }
        val out = try { block() } catch (e: Exception) { ActionOutcome.Failed("$what failed: ${e.message}") }
        _ui.update { it.copy(busy = false, outcome = out) }
    }

    fun issue(amountText: String, label: String) = act { s, t -> val u = PrepaidAmount.parseCkbToUnits(amountText) ?: return@act ActionOutcome.Failed("enter an amount in CKB, up to three decimals"); s.issue(t, u, label, ::phase) }
    fun load(amountText: String) = act { s, t -> val c = (ui.value.tap as? TapClass.Known)?.card ?: return@act ActionOutcome.Failed("no card")
        val u = PrepaidAmount.parseCkbToUnits(amountText) ?: return@act ActionOutcome.Failed("enter an amount in CKB, up to three decimals"); s.load(t, c, u, ::phase) }
    fun resume() = act { s, t -> val c = (ui.value.tap as? TapClass.Resume)?.card ?: return@act ActionOutcome.Failed("no card"); s.resume(t, c, ::phase) }
    fun adopt() = act { s, t -> val r = (ui.value.tap as? TapClass.Bench)?.record ?: return@act ActionOutcome.Failed("no record"); s.adopt(t, r, ::phase) }
    fun repair() = act { s, t -> val c = (ui.value.tap as? TapClass.Damaged)?.card ?: return@act ActionOutcome.Failed("no card"); s.repair(t, c, ::phase) }
    fun cashOut(address: String) = act { s, t -> val c = tappedCard() ?: return@act ActionOutcome.Failed("no card"); s.cashOut(t, c, address, ::phase) }
    fun revoke(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("revoke") { s.revoke(card) } } }
    fun reinstate(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("reinstate") { s.reinstate(card) } } }
    fun voidCard(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("void") { s.void(card, tag?.takeIf { it.uidHex.equals(card.uid, true) }, ::phase) } } }
    suspend fun preview(card: PrepaidCardEntity): CashOutPreview? = svc?.inspectForRetire(tag, card)

    fun eventsFlow(): Flow<List<PrepaidEventEntity>> = gateway.prepaidStore().eventsFlow()
    suspend fun eventsFor(reserveId: String) = gateway.prepaidStore().eventsForCard(reserveId)
    suspend fun card(reserveId: String) = gateway.prepaidStore().card(reserveId)
    suspend fun liveCell(card: PrepaidCardEntity): Utxo? = gateway.prepaidLiveCell(card.currentOutpoint)
    suspend fun allEventsCsv(): String = AuditCsv.render(gateway.prepaidStore().allEvents())
    fun tagRecordFor(card: PrepaidCardEntity): TapClass.Known? = (ui.value.tap as? TapClass.Known)?.takeIf { it.card.reserveId == card.reserveId }

    fun tappedCard(): PrepaidCardEntity? = when (val t = ui.value.tap) { is TapClass.Known -> t.card; is TapClass.Damaged -> t.card; is TapClass.Resume -> t.card; else -> null }

    private fun act(block: suspend (PrepaidService, TagPort) -> ActionOutcome) = viewModelScope.launch {
        val s = svc ?: return@launch; val t = tag ?: run { _ui.update { it.copy(outcome = ActionOutcome.Failed("tap the card first")) }; return@launch }
        run("action") { block(s, t) }
    }
}
