package com.wyltek.wallet.core.chain

import com.wyltek.wallet.core.model.*

interface ChainProvider {
    val name: String
    val isActive: Boolean

    suspend fun getCellsByLock(
        lockScript: LockScript,
       scripción: String? = null
    ): List<Utxo>

    suspend fun getTipHeader(): HeaderInfo?

    suspend fun estimateFee(rate: ULong): ULong

    suspend fun sendTransaction(transaction: Transaction): String?

    suspend fun getTransactionStatus(txHash: String): TxStatus?
}

data class HeaderInfo(
    val hash: String,
    val number: ULong,
    val epoch: String,
    val parentHash: String,
    val timestamp: ULong
)

enum class TxStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    UNKNOWN
}

class RpcProfile(
    override val name: String,
    val url: String,
    val isPrivate: Boolean = false,
    override var isActive: Boolean = true
) : ChainProvider {

    override suspend fun getCellsByLock(
        lockScript: LockScript,
       筛选: String?
    ): List<Utxo> {
        TODO("Implement RPC call: get_cells_by_lock")
    }

    override suspend fun getTipHeader(): HeaderInfo? {
        TODO("Implement RPC call: get_tip_header")
    }

    override suspend fun estimateFee(rate: ULong): ULong {
        TODO("Implement RPC call: estimate_fee")
    }

    override suspend fun sendTransaction(transaction: Transaction): String? {
        TODO("Implement RPC call: send_transaction")
    }

    override suspend fun getTransactionStatus(txHash: String): TxStatus? {
        TODO("Implement RPC call: get_transaction_status")
    }
}

class ChainManager {
    private val providers = mutableListOf<ChainProvider>()
    private var activeProvider: ChainProvider? = null

    fun addProvider(provider: ChainProvider) {
        providers.add(provider)
        if (activeProvider == null) {
            activeProvider = provider
        }
    }

    fun setActiveProvider(name: String) {
        activeProvider = providers.find { it.name == name }
    }

    fun getActiveProvider(): ChainProvider? = activeProvider

    fun getAllProviders(): List<ChainProvider> = providers.toList()

    suspend fun getCellsByLock(lockScript: LockScript): List<Utxo> {
        return activeProvider?.getCellsByLock(lockScript) ?: emptyList()
    }

    suspend fun getTipHeader(): HeaderInfo? {
        return activeProvider?.getTipHeader()
    }

    suspend fun sendTransaction(transaction: Transaction): String? {
        return activeProvider?.sendTransaction(transaction)
    }
}
