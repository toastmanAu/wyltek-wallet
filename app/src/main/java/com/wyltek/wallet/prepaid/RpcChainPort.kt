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
    override suspend fun broadcast(txJson: JsonObject, txHash: String): Boolean {
        val sent = chainManager.sendTransactionJson(txJson) ?: return false
        return sent.equals(txHash, ignoreCase = true)
    }
    override suspend fun status(txHash: String): TxStatus? = chainManager.getActiveProvider()?.getTransactionStatus(txHash)
}
