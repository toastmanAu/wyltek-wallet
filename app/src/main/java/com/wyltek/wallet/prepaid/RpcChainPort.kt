package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import kotlinx.serialization.json.JsonObject

class RpcChainPort(private val chainManager: ChainManager) : ChainPort {
    override suspend fun cellsByLock(lock: LockScript): List<Utxo> = chainManager.getCellsByLock(lock)
    override suspend fun cellAt(outpoint: String): Utxo? {
        // Reserve cells always carry the merchant lock; the index records only the outpoint, so scan by lock.
        val (tx, idx) = outpoint.split(":").let { it[0] to it[1].toUInt() }
        return cachedLock?.let { lock -> chainManager.getCellsByLock(lock).firstOrNull { it.outPoint.txHash == tx && it.outPoint.index == idx } }
    }
    /** Set by the gateway once the merchant lock is known; cellAt needs a lock to query the indexer. */
    var cachedLock: LockScript? = null
    /**
     * True only when the node returned the built hash. A genuine node refusal cannot be distinguished
     * from a lost response at this layer (ChainProvider swallows exceptions into null), so both throw
     * IOException and surface as "still confirming" rather than deleting a possibly-funded reserve.
     */
    override suspend fun broadcast(txJson: JsonObject, txHash: String): Boolean =
        broadcastOutcome(chainManager.sendTransactionJson(txJson), txHash)
    override suspend fun status(txHash: String): TxStatus? = chainManager.getActiveProvider()?.getTransactionStatus(txHash)
}

/** True on a case-insensitive hash match; throws [java.io.IOException] on null or mismatch (outcome unknown). */
fun broadcastOutcome(returned: String?, expected: String): Boolean {
    if (returned != null && returned.equals(expected, ignoreCase = true)) return true
    throw java.io.IOException("broadcast outcome unknown")
}
