package com.wyltek.wallet.agent

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.wyltek.wallet.agent.relay.RelayPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.server.AccountInfo
import com.wyltek.wallet.agent.server.AgentDispatchPort
import com.wyltek.wallet.agent.server.ChainProxyResult
import com.wyltek.wallet.agent.server.IntentStatusResponse
import com.wyltek.wallet.agent.service.AgentNotifications
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.model.DaoDeposit
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.core.native.Intent
import com.wyltek.wallet.data.WalletRepository
import com.wyltek.wallet.data.WalletResult
import java.math.BigInteger

/**
 * Facade that wires all on-device Agent Gateway components from a [Context].
 * Manual DI — mirrors the pattern used by WalletRepository itself.
 *
 * Implements [AgentDispatchPort] so [AgentGatewayService] can pass `agentGateway`
 * directly to `agentModule(port)` without an extra adapter.
 */
class AgentGateway(context: Context) : AgentDispatchPort {
    private val app = context.applicationContext
    internal val secure = AgentSecureStore(app)
    private val repository = WalletRepository(app)
    internal val keyStore = AgentKeyStore(secure, repository.getStrongBoxManager())
    private val db = AgentDatabaseFactory.open(app, secure.sqlcipherPassphrase())
    private val ledger = AgentLedger(db)

    val tokenService = AgentTokenService(keyStore, db)
    val pendingStore = PendingStore(db)

    private val gatewayScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private val inFlightRelay = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val dispatcher = AgentActionDispatcher(
        keyStore = keyStore,
        ledger = ledger,
        tokenService = tokenService,
        pendingStore = pendingStore,
        resolveAccount = { addr ->
            repository.getAllAccounts().firstOrNull { a -> a.addresses.any { it.bech32m == addr } }
        },
        sendCkb = { acct, to, amount ->
            // Source = the token's bound account address (its first address). Hybrid multi-address
            // source selection is a B2 refinement.
            repository.sendCkb(acct, to, amount.toULong(), fromCkbAddress = acct.addresses.firstOrNull())
        },
        sendToken = { acct, assetId, to, amount ->
            val ts = parseTypeScript(assetId)
            if (ts == null) WalletResult.Error("bad asset id: $assetId") else repository.sendToken(acct, ts, to, BigInteger.valueOf(amount))
        },
        daoDeposit = { acct, amount ->
            repository.depositDao(acct, amount.toULong())
        },
        daoWithdraw = { acct, daoRef ->
            val deposit = resolveDeposit(acct, daoRef)
            if (deposit == null) WalletResult.Error("deposit not found: $daoRef") else repository.withdrawDaoPhase1(acct, deposit)
        },
        daoClaim = { acct, daoRef ->
            val deposit = resolveDeposit(acct, daoRef)
            if (deposit == null) WalletResult.Error("deposit not found: $daoRef") else repository.claimDao(acct, deposit)
        },
        messaging = CempMessagingSender(repository)
    )

    // ── AgentDispatchPort ──────────────────────────────────────────────────────

    override suspend fun dispatch(
        token: String,
        intent: Intent,
        sourceIp: String,
        nowUnix: Long
    ): DispatchResult {
        val r = dispatcher.dispatch(token, intent, sourceIp, nowUnix)
        if (r is DispatchResult.Approval) {
            AgentNotifications.ensureChannels(app)
            // r.amount is in shannons (1 CKB = 1e8). Format to CKB — matching the
            // pending-row formatter in AgentViewModel — so the approval prompt
            // doesn't show the raw shannon value (which reads as a huge number).
            val amountCkb = "%.4f".format(r.amount / 100_000_000.0)
            val summary = "${intent.op} $amountCkb ${r.asset} to ${intent.to}"
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(r.pendingId.toInt(), AgentNotifications.approvalNotification(app, r.pendingId, summary))
        }
        return r
    }

    /**
     * Report a completed approval back to the relay.
     *
     * Without this the relay never learns the outcome: [RelayClient] posts
     * `needs_approval` when the intent arrives and then forgets it, so a POS (or any
     * agent) polling `GET /relay/intent/{id}` sees `needs_approval` forever and times
     * out — even though the transaction was signed and broadcast. No-ops for intents
     * that did not originate from the relay (`relay_intent_id` null), e.g. ones served
     * over the on-device HTTP gateway.
     */
    suspend fun reportRelayResult(pendingId: Long, status: String, txHash: String?, error: String?) {
        val row = pendingStore.get(pendingId) ?: return
        val relayIntentId = row.relayIntentId ?: return
        val cfg = RelayPairing.loadConfig(secure) ?: return
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("device_token", cfg.deviceToken)
                .put("intent_id", relayIntentId)
                .put("status", status)
                .put("tx_hash", txHash)
                .put("error", error)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            try {
                OkHttpClient().newCall(
                    Request.Builder().url(cfg.resultUrl).post(body).build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) Log.w(TAG, "relay result POST ${resp.code} for $relayIntentId")
                    else Log.i(TAG, "relay result POST ok: $relayIntentId -> $status")
                }
            } catch (e: Exception) {
                Log.e(TAG, "relay result POST failed for $relayIntentId: ${e.message}")
            }
        }
    }

    override suspend fun pendingStatus(id: Long): IntentStatusResponse? {
        val entity = pendingStore.get(id) ?: return null
        return IntentStatusResponse(
            status = entity.status,
            txHash = entity.resultTxHash,
            error = entity.resultError
        )
    }

    override suspend fun accounts(): List<AccountInfo> =
        tokenService.list().map { t ->
            AccountInfo(tokenId = t.tokenId, account = t.account, revoked = t.revoked)
        }

    override suspend fun submitRelayIntent(
        token: String, intent: Intent, sourceIp: String, nowUnix: Long
    ): String {
        val intentId = relayIntentId(token, intent.nonce)
        // Idempotent: if we already track this intent_id, don't re-dispatch.
        if (pendingStore.getByRelayIntentId(intentId) != null) return intentId
        // Concurrent-duplicate guard: only one racing POST for this intent_id launches
        // dispatch; the other returns the same intent_id and the POS polls the single row.
        if (!inFlightRelay.add(intentId)) return intentId

        gatewayScope.launch {
            try {
                val result = try {
                    dispatch(token, intent, sourceIp, nowUnix)
                } catch (e: Throwable) {
                    pendingStore.putTerminalByRelayIntent(
                        intentId, intent, sourceIp, nowUnix,
                        com.wyltek.wallet.agent.db.PENDING_FAILED, null, e.message ?: "dispatch error"
                    )
                    return@launch
                }
                when (result) {
                    is DispatchResult.Sent -> pendingStore.putTerminalByRelayIntent(
                        intentId, intent, sourceIp, nowUnix,
                        com.wyltek.wallet.agent.db.PENDING_SENT, result.txHash, null
                    )
                    is DispatchResult.Denied -> pendingStore.putTerminalByRelayIntent(
                        intentId, intent, sourceIp, nowUnix,
                        com.wyltek.wallet.agent.db.PENDING_DENIED, null, result.reason
                    )
                    is DispatchResult.Failed -> pendingStore.putTerminalByRelayIntent(
                        intentId, intent, sourceIp, nowUnix,
                        com.wyltek.wallet.agent.db.PENDING_FAILED, null, result.message
                    )
                    is DispatchResult.Approval ->
                        // The approval row already exists (dispatch created it); make it findable by
                        // intent_id so the merchant's later Approve updates the row the POS polls.
                        pendingStore.linkRelayIntent(result.pendingId, intentId)
                }
            } finally {
                inFlightRelay.remove(intentId)
            }
        }
        return intentId
    }

    override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? {
        val row = pendingStore.getByRelayIntentId(intentId) ?: return null
        return IntentStatusResponse(
            status = row.status, txHash = row.resultTxHash, error = row.resultError
        )
    }

    /**
     * Token-gated, method-whitelisted verbatim proxy of a CKB read RPC to the phone's own
     * active node — lets a Tier-1 POS confirm sales / read balance through the phone instead
     * of standing up its own indexer. Reads only; the token check here is a PRESENCE check
     * (registered + not revoked), not a full biscuit spend-authorization — nothing is spent.
     */
    override suspend fun proxyChainRead(token: String, method: String, rawBody: String): ChainProxyResult {
        val entry = db.agentDao().tokenByString(token)
        if (entry == null || entry.revoked) return ChainProxyResult.BadToken
        if (method != "get_cells_capacity" && method != "get_transactions") return ChainProxyResult.BadMethod
        val nodeUrl = repository.activeNodeUrl() ?: return ChainProxyResult.Upstream("no active node")
        return withContext(Dispatchers.IO) {
            try {
                OkHttpClient().newCall(
                    Request.Builder().url(nodeUrl)
                        .post(rawBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) ChainProxyResult.Upstream("node HTTP ${resp.code}")
                    else ChainProxyResult.Ok(resp.body?.string() ?: "")
                }
            } catch (e: Exception) { ChainProxyResult.Upstream(e.message ?: "node error") }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Parse an asset ID of the form "codeHash:hashType:args" into a LockScript.
     * Returns null if the format is invalid.
     */
    private fun parseTypeScript(assetId: String): LockScript? {
        val parts = assetId.split(":")
        if (parts.size != 3) return null
        return LockScript(codeHash = parts[0], hashType = parts[1], args = parts[2])
    }

    /**
     * Resolve a "txHash:index" dao_ref to a DaoDeposit by scanning the account's live deposits.
     * Returns null if the ref is malformed or not found on chain.
     */
    private suspend fun resolveDeposit(acct: WalletAccount, daoRef: String): DaoDeposit? {
        val lock = acct.addresses.firstOrNull()?.lockScript ?: return null
        val scan = repository.scanDaoDeposits(lock, acct.network)
        val list = (scan as? WalletResult.Success)?.data ?: return null
        return list.firstOrNull { matchesOutpoint(it, daoRef) }
    }

    /**
     * Match a DaoDeposit against a "txHash:index" reference string.
     * DaoDeposit.outPoint has txHash: String and index: UInt.
     */
    private fun matchesOutpoint(deposit: DaoDeposit, daoRef: String): Boolean {
        val parts = daoRef.split(":")
        if (parts.size != 2) return false
        val idx = parts[1].toUIntOrNull() ?: return false
        return deposit.outPoint.txHash == parts[0] && deposit.outPoint.index == idx
    }

    companion object {
        private const val TAG = "AgentGateway"
    }
}
