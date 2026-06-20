package com.wyltek.wallet.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.agent.service.AgentGatewayService
import com.wyltek.wallet.agent.server.Tailnet
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

data class AgentUiState(
    val tokens: List<TokenRow> = emptyList(),
    val serverRunning: Boolean = false,
    val bindAddress: String? = null,
    val pendingCount: Int = 0,
    val lastMintedToken: String? = null,
    val error: String? = null
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val gateway = (application as WyltekWalletApp).agentGateway

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(
            tokens = gateway.tokenService.list().map { TokenRow(it.tokenId, it.account, it.revoked) },
            serverRunning = AgentGatewayService.isRunning(),
            bindAddress = Tailnet.bindAddress(),
            pendingCount = gateway.dispatcher.listPending().size
        )
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
}
