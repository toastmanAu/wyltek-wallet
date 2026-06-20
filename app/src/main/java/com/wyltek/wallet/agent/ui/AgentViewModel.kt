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
import com.wyltek.wallet.agent.relay.RelayPairing
import com.wyltek.wallet.core.native.TokenSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val relayUrl: String? = null
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
        _uiState.value = _uiState.value.copy(
            tokens = gateway.tokenService.list().map { TokenRow(it.tokenId, it.account, it.revoked) },
            serverRunning = AgentGatewayService.isRunning(),
            bindAddress = Tailnet.bindAddress(),
            pendingCount = gateway.dispatcher.listPending().size,
            relayPaired = relayUrl != null,
            relayUrl = relayUrl
        )
    }

    fun pairRelay(baseUrl: String) = viewModelScope.launch {
        try {
            val pairing = RelayPairing(gateway.keyStore, gateway.secure)
            val ok = pairing.pair(baseUrl)
            if (ok) {
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(error = "Relay pairing failed — check the URL and try again")
            }
        } catch (e: Throwable) {
            _uiState.value = _uiState.value.copy(error = e.message ?: "Relay pairing error")
        }
    }

    fun unpairRelay() {
        gateway.secure.deleteBlob(RelayPairing.KEY_RELAY_BASE_URL)
        gateway.secure.deleteBlob(RelayPairing.KEY_RELAY_DEVICE_TOKEN)
        gateway.secure.deleteBlob(RelayPairing.KEY_RELAY_DEVICE_ID)
        _uiState.value = _uiState.value.copy(relayPaired = false, relayUrl = null)
    }

    fun startServer() {
        AgentGatewayService.start(getApplication())
        _uiState.value = _uiState.value.copy(serverRunning = true)
        refresh()
    }

    fun stopServer() {
        AgentGatewayService.stop(getApplication())
        _uiState.value = _uiState.value.copy(serverRunning = false)
        refresh()
    }

    fun mint(spec: TokenSpec) = viewModelScope.launch {
        try {
            val m = gateway.tokenService.mint(spec)
            _uiState.value = _uiState.value.copy(lastMintedToken = m.token)
            refresh()
        } catch (e: Throwable) {
            _uiState.value = _uiState.value.copy(error = e.message)
        }
    }

    fun revoke(tokenId: String) = viewModelScope.launch {
        gateway.tokenService.revoke(tokenId)
        refresh()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearLastMintedToken() {
        _uiState.value = _uiState.value.copy(lastMintedToken = null)
    }

    fun loadPending() = viewModelScope.launch {
        val rows = gateway.dispatcher.listPending().map { p ->
            PendingRow(
                id = p.id,
                op = p.op,
                asset = p.asset,
                amount = "%.4f".format(p.amount / 100_000_000.0),
                to = p.to,
                action = p.action
            )
        }
        _uiState.value = _uiState.value.copy(pending = rows, pendingCount = rows.size)
    }

    fun approve(pendingId: Long) = viewModelScope.launch {
        val result = gateway.dispatcher.executeApproved(pendingId)
        val error = when (result) {
            is DispatchResult.Denied -> result.reason
            is DispatchResult.Failed -> result.message
            else -> null
        }
        _uiState.value = _uiState.value.copy(error = error)
        loadPending()
    }

    fun reject(pendingId: Long) = viewModelScope.launch {
        val p = gateway.pendingStore.get(pendingId)
        if (p != null && p.status == PENDING_APPROVAL) {
            gateway.pendingStore.setResult(pendingId, PENDING_DENIED, null, "rejected by user")
        }
        loadPending()
    }
}
