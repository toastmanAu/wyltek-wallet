package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.RpcProfile
import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import kotlinx.serialization.json.JsonObject

/**
 * [strictStatus] returns the node's raw `tx_status.status` and THROWS on any transport or RPC
 * failure; the default asks the active provider. It is a seam so the status mapping is host-testable.
 */
class RpcChainPort(
    private val chainManager: ChainManager,
    private val strictStatus: suspend (String) -> String = { hash ->
        val rpc = chainManager.getActiveProvider() as? RpcProfile ?: throw java.io.IOException("no RPC provider is active")
        rpc.getTransactionStatusStrict(hash)
    },
) : ChainPort {
    override suspend fun cellsByLock(lock: LockScript): List<Utxo> = chainManager.getCellsByLock(lock)
    override suspend fun cellAt(outpoint: String): Utxo? {
        // Reserve cells always carry the merchant lock; the index records only the outpoint, so scan by lock.
        val key = normalizeOutpoint(outpoint)
        return cachedLock?.let { lock -> chainManager.getCellsByLock(lock).firstOrNull { PrepaidTxPlanner.outpointKey(it) == key } }
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
    /** Throws when the node cannot be asked; [TxStatus.UNKNOWN] only when the node explicitly answers "unknown". */
    override suspend fun status(txHash: String): TxStatus = txStatusOf(strictStatus(ensure0x(txHash)))
}

/** True on a hash match after [ensure0x] on both sides; throws [java.io.IOException] on null or mismatch (outcome unknown). */
fun broadcastOutcome(returned: String?, expected: String): Boolean {
    if (returned != null && ensure0x(returned) == ensure0x(expected)) return true
    throw java.io.IOException("broadcast outcome unknown")
}

/** The node's `tx_status.status` → [TxStatus]. Anything unrecognised throws: it is not an explicit "unknown". */
fun txStatusOf(raw: String): TxStatus = when (raw) {
    "pending", "proposed" -> TxStatus.PENDING
    "committed" -> TxStatus.CONFIRMED
    "rejected" -> TxStatus.REJECTED
    "unknown" -> TxStatus.UNKNOWN
    else -> throw java.io.IOException("unexpected tx status '$raw'")
}
