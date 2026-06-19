package com.wyltek.wallet.agent

import android.content.Context
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.model.DaoDeposit
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.data.WalletRepository
import com.wyltek.wallet.data.WalletResult
import java.math.BigInteger

/**
 * Facade that wires all on-device Agent Gateway components from a [Context].
 * Manual DI — mirrors the pattern used by WalletRepository itself.
 */
class AgentGateway(context: Context) {
    private val app = context.applicationContext
    private val secure = AgentSecureStore(app)
    private val repository = WalletRepository(app)
    private val keyStore = AgentKeyStore(secure, repository.getStrongBoxManager())
    private val db = AgentDatabaseFactory.open(app, secure.sqlcipherPassphrase())
    private val ledger = AgentLedger(db)

    val tokenService = AgentTokenService(keyStore, db)

    val dispatcher = AgentActionDispatcher(
        keyStore = keyStore,
        ledger = ledger,
        tokenService = tokenService,
        resolveAccount = { addr ->
            repository.getAllAccounts().firstOrNull { a -> a.addresses.any { it.bech32m == addr } }
        },
        sendCkb = { acct, to, amount ->
            repository.sendCkb(acct, to, amount.toULong())
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
        messaging = UnsupportedMessagingSender()
    )

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
