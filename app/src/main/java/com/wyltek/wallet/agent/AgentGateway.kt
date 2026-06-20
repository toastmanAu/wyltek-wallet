package com.wyltek.wallet.agent

import android.app.NotificationManager
import android.content.Context
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.server.AccountInfo
import com.wyltek.wallet.agent.server.AgentDispatchPort
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
            val summary = "${intent.op} ${r.amount} ${r.asset} to ${intent.to}"
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(r.pendingId.toInt(), AgentNotifications.approvalNotification(app, r.pendingId, summary))
        }
        return r
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
}
