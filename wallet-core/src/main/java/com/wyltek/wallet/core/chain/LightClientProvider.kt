package com.wyltek.wallet.core.chain

import com.wyltek.wallet.core.model.*
import kotlinx.serialization.json.JsonElement

/**
 * A [ChainProvider] backed by the embedded light client's localhost JSON-RPC.
 * The light client serves the identical indexer surface as a full node's
 * indexer, so every query delegates to an internal [RpcProfile]. The one extra
 * concern is [registerScripts] — the light client only returns cells for lock
 * scripts registered via `set_scripts`.
 */
class LightClientProvider(
    val url: String,
    override var isActive: Boolean = false
) : ChainProvider {

    override val name: String = NAME

    private val inner = RpcProfile(NAME, url, isPrivate = true, isActive = isActive)
    private val client = CkbRpcClient(url)

    /** Tell the light client which locks to watch, syncing from [startBlockHex]. */
    suspend fun registerScripts(locks: List<LockScript>, startBlockHex: String) {
        client.setScripts(buildScriptStatuses(locks, startBlockHex))
    }

    suspend fun getRegisteredScripts(): List<ScriptStatus> = client.getScripts()

    // --- ChainProvider delegation ---
    override suspend fun getCellsByLock(lockScript: LockScript) = inner.getCellsByLock(lockScript)
    override suspend fun getCellsByLockAndType(lockScript: LockScript, typeScript: LockScript) =
        inner.getCellsByLockAndType(lockScript, typeScript)
    override suspend fun getCellsCapacity(lockScript: LockScript) = inner.getCellsCapacity(lockScript)
    override suspend fun getTipHeader() = inner.getTipHeader()
    override suspend fun getHeaderByNumber(blockNumber: String) = inner.getHeaderByNumber(blockNumber)
    override suspend fun estimateFee(rate: ULong) = inner.estimateFee(rate)
    override suspend fun sendTransaction(transaction: Transaction) = inner.sendTransaction(transaction)
    override suspend fun sendTransactionJson(txJson: JsonElement) = inner.sendTransactionJson(txJson)
    override suspend fun getTransactionStatus(txHash: String) = inner.getTransactionStatus(txHash)
    override suspend fun getTransactionsByLock(lockScript: LockScript) = inner.getTransactionsByLock(lockScript)
    override suspend fun getTransactionDetail(txHash: String) = inner.getTransactionDetail(txHash)
    override suspend fun calculateDaoMaximumWithdraw(
        depositTxHash: String, depositIndex: String, withdrawBlockHash: String
    ) = inner.calculateDaoMaximumWithdraw(depositTxHash, depositIndex, withdrawBlockHash)

    companion object {
        const val NAME = "Embedded Light Client"

        /** Pure builder: one watched lock ScriptStatus per [locks] at [startBlockHex]. */
        fun buildScriptStatuses(locks: List<LockScript>, startBlockHex: String): List<ScriptStatus> =
            locks.map {
                ScriptStatus(
                    script = Script(
                        code_hash = if (it.codeHash.startsWith("0x", true)) it.codeHash else "0x${it.codeHash}",
                        hash_type = it.hashType,
                        args = if (it.args.startsWith("0x", true)) it.args else "0x${it.args}"
                    ),
                    blockNumber = startBlockHex
                )
            }
    }
}
