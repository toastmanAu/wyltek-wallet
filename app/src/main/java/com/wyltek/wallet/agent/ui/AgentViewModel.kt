package com.wyltek.wallet.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.agent.service.AgentGatewayService
import com.wyltek.wallet.agent.server.Tailnet
import com.wyltek.wallet.agent.db.PENDING_APPROVAL
import com.wyltek.wallet.agent.db.PENDING_DENIED
import com.wyltek.wallet.agent.db.PENDING_FAILED
import com.wyltek.wallet.agent.db.PENDING_SENT
import com.wyltek.wallet.agent.relay.RelayPairing
import com.wyltek.wallet.core.native.TokenSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TokenRow(
    val tokenId: String,
    val account: String,
    val revoked: Boolean
)

data class PendingRow(
    val id: Long,
    val op: String,
    val asset: String,
    val amount: String,
    val to: String,
    val action: String?
)

data class AgentUiState(
    val tokens: List<TokenRow> = emptyList(),
    val pending: List<PendingRow> = emptyList(),
    val serverRunning: Boolean = false,
    val bindAddress: String? = null,
    val pendingCount: Int = 0,
    val lastMintedToken: String? = null,
    val error: String? = null,
    val relayPaired: Boolean = false,
    val relayUrl: String? = null,
    val deviceId: String? = null
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val gateway = (application as WyltekWalletApp).agentGateway

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val relayUrl = gateway.secure.loadBlob(RelayPairing.KEY_RELAY_BASE_URL)
            ?.let { String(it, Charsets.UTF_8) }
        // Read everything BEFORE touching state. `update` re-runs its lambda on
        // contention, so the lambda must be pure — no suspend calls, no I/O.
        val tokenRows  = gateway.tokenService.list().map { TokenRow(it.tokenId, it.account, it.revoked) }
        val running    = AgentGatewayService.isRunning()
        val bind       = Tailnet.bindAddress()
        val pendingNow = gateway.dispatcher.listPending().size
        val devId      = RelayPairing.deviceId(gateway.secure)
        _uiState.update { st ->
            st.copy(
                tokens = tokenRows,
                serverRunning = running,
                bindAddress = bind,
                pendingCount = pendingNow,
                relayPaired = relayUrl != null,
                relayUrl = relayUrl,
                deviceId = devId
            )
        }
    }

    fun pairRelay(baseUrl: String) = viewModelScope.launch {
        try {
            val pairing = RelayPairing(gateway.keyStore, gateway.secure)
            val ok = pairing.pair(baseUrl)
            if (ok) {
                refresh()
            } else {
                _uiState.update { it.copy(error = "Relay pairing failed — check the URL and try again") }
            }
        } catch (e: Throwable) {
            _uiState.update { it.copy(error = e.message ?: "Relay pairing error") }
        }
    }

    fun unpairRelay() {
        gateway.secure.deleteBlob(RelayPairing.KEY_RELAY_BASE_URL)
        gateway.secure.deleteBlob(RelayPairing.KEY_RELAY_DEVICE_TOKEN)
        gateway.secure.deleteBlob(RelayPairing.KEY_RELAY_DEVICE_ID)
        _uiState.update { it.copy(relayPaired = false, relayUrl = null, deviceId = null) }
    }

    fun startServer() {
        AgentGatewayService.start(getApplication())
        _uiState.update { it.copy(serverRunning = true) }
        refresh()
    }

    fun stopServer() {
        AgentGatewayService.stop(getApplication())
        _uiState.update { it.copy(serverRunning = false) }
        refresh()
    }

    fun mint(spec: TokenSpec) = viewModelScope.launch {
        try {
            val m = gateway.tokenService.mint(spec)
            _uiState.update { it.copy(lastMintedToken = m.token) }
            refresh()
        } catch (e: Throwable) {
            _uiState.update { it.copy(error = e.message) }
        }
    }

    fun revoke(tokenId: String) = viewModelScope.launch {
        gateway.tokenService.revoke(tokenId)
        refresh()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearLastMintedToken() {
        _uiState.update { it.copy(lastMintedToken = null) }
    }

    /**
     * Live approval queue. Room re-emits [pendingFlow] on every write to the table, so an
     * intent pushed by the relay while this screen is open appears on its own — no manual
     * refresh, and no race against [refresh] (which no longer touches `pending`).
     */
    private var pendingJob: Job? = null

    fun observePending() {
        if (pendingJob?.isActive == true) return
        pendingJob = viewModelScope.launch {
            gateway.pendingStore.pendingFlow().collect { entities ->
                val rows = entities.map { p ->
                    PendingRow(
                        id = p.id,
                        op = p.op,
                        asset = p.asset,
                        amount = "%.4f".format(p.amount / 100_000_000.0),
                        to = p.to,
                        action = p.action
                    )
                }
                _uiState.update { it.copy(pending = rows, pendingCount = rows.size) }
            }
        }
    }

    fun approve(pendingId: Long) = viewModelScope.launch {
        val result = gateway.dispatcher.executeApproved(pendingId)
        val error = when (result) {
            is DispatchResult.Denied -> result.reason
            is DispatchResult.Failed -> result.message
            else -> null
        }
        // Tell the relay how it ended. The waiting agent (e.g. the Blackbox POS) polls the
        // relay, not this device — without this it sees `needs_approval` until it times out,
        // even on a successful broadcast.
        val (relayStatus, txHash) = when (result) {
            is DispatchResult.Sent   -> PENDING_SENT to result.txHash
            is DispatchResult.Denied -> PENDING_DENIED to null
            else                     -> PENDING_FAILED to null
        }
        gateway.reportRelayResult(pendingId, relayStatus, txHash, error)
        _uiState.update { it.copy(error = error) }
    }

    fun reject(pendingId: Long) = viewModelScope.launch {
        val p = gateway.pendingStore.get(pendingId)
        if (p != null && p.status == PENDING_APPROVAL) {
            gateway.pendingStore.setResult(pendingId, PENDING_DENIED, null, "rejected by user")
            gateway.reportRelayResult(pendingId, PENDING_DENIED, null, "rejected by user")
        }
    }
}
