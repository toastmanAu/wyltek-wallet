package com.wyltek.wallet.lightclient

import android.content.Context
import com.wyltek.wallet.core.chain.LightClientProvider
import com.wyltek.wallet.core.model.LockScript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LightClientState { STOPPED, STARTING, SYNCING, READY, ERROR }

/** Pure state classifier — decides READY vs SYNCING from tip lag. */
fun classifyState(
    running: Boolean,
    clientTip: ULong?,
    networkTip: ULong?,
    lagThreshold: ULong = 50u
): LightClientState = when {
    !running -> LightClientState.STOPPED
    clientTip == null -> LightClientState.STARTING
    networkTip == null -> LightClientState.SYNCING
    networkTip > clientTip && (networkTip - clientTip) > lagThreshold -> LightClientState.SYNCING
    else -> LightClientState.READY
}

/**
 * Orchestrates the light-client lifecycle: start the service, build a provider,
 * register the wallet's locks, and expose a [state] flow for the UI.
 */
class LightClientController(private val appContext: Context) {
    val provider = LightClientProvider(LightClientService.RPC_URL)
    private val _state = MutableStateFlow(LightClientState.STOPPED)
    val state: StateFlow<LightClientState> = _state

    fun stop() {
        LightClientService.stop(appContext)
        _state.value = LightClientState.STOPPED
    }

    /** Start the service; [refresh] advances the state as the client syncs. */
    fun start() {
        LightClientService.start(appContext)
        _state.value = LightClientState.STARTING
    }

    /** Re-evaluate state and (once the RPC is up) register scripts if needed. */
    suspend fun refresh(locks: List<LockScript>, startBlockHex: String, networkTip: ULong?) {
        if (!LightClientService.isRunning()) { _state.value = LightClientState.STOPPED; return }
        val clientTip = runCatching { provider.getTipHeader()?.number }.getOrNull()
        if (clientTip != null && provider.getRegisteredScriptsSafe().isEmpty()) {
            runCatching { provider.registerScripts(locks, startBlockHex) }
        }
        _state.value = classifyState(true, clientTip, networkTip)
    }
}

private suspend fun LightClientProvider.getRegisteredScriptsSafe() =
    runCatching { getRegisteredScripts() }.getOrDefault(emptyList())
