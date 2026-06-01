package com.wyltek.wallet.core.chain

import com.wyltek.wallet.core.model.*
import kotlinx.serialization.json.*

interface ChainProvider {
    val name: String
    val isActive: Boolean

    suspend fun getCellsByLock(lockScript: LockScript): List<Utxo>

    suspend fun getCellsCapacity(lockScript: LockScript): CellsCapacity

    suspend fun getTipHeader(): HeaderInfo?

    suspend fun estimateFee(rate: ULong): ULong

    suspend fun sendTransaction(transaction: Transaction): String?

    suspend fun getTransactionStatus(txHash: String): TxStatus?
}

data class CellsCapacity(
    val totalCapacity: ULong,
    val occupiedCapacity: ULong,
    val availableCapacity: ULong
)

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

    private val client = CkbRpcClient(url)

    override suspend fun getCellsByLock(lockScript: LockScript): List<Utxo> {
        val script = Script(
            code_hash = lockScript.codeHash,
            hash_type = lockScript.hashType,
            args = lockScript.args
        )

        val cells = mutableListOf<Utxo>()
        var cursor: String? = null

        do {
            val response = client.getCellsByLock(script, afterCursor = cursor)
            for (cell in response.objects) {
                val capacity = cell.output.capacity.removePrefix("0x").toULong(16)
                val outPoint = com.wyltek.wallet.core.model.OutPoint(
                    txHash = cell.out_point.tx_hash,
                    index = cell.out_point.index.removePrefix("0x").toUInt(16)
                )
                val lock = LockScript(
                    codeHash = cell.output.lock.code_hash,
                    hashType = cell.output.lock.hash_type,
                    args = cell.output.lock.args
                )
                val typeScript = cell.output.type_?.let {
                    LockScript(
                        codeHash = it.code_hash,
                        hashType = it.hash_type,
                        args = it.args
                    )
                }
                val data = cell.output_data.data

                cells.add(
                    Utxo(
                        outPoint = outPoint,
                        capacity = capacity,
                        lock = lock,
                        type_ = typeScript,
                        data = if (data.isNotEmpty()) data else null
                    )
                )
            }
            cursor = if (response.objects.isNotEmpty()) response.last_cursor else null
        } while (cursor != null)

        return cells
    }

    override suspend fun getCellsCapacity(lockScript: LockScript): CellsCapacity {
        val script = Script(
            code_hash = lockScript.codeHash,
            hash_type = lockScript.hashType,
            args = lockScript.args
        )
        val response = client.getCellsCapacity(script)
        val total = response.capacity.removePrefix("0x").toULong(16)
        val occupied = response.occupied_capacity.removePrefix("0x").toULong(16)
        return CellsCapacity(
            totalCapacity = total,
            occupiedCapacity = occupied,
            availableCapacity = total - occupied
        )
    }

    override suspend fun getTipHeader(): HeaderInfo? {
        return try {
            val header = client.getTipHeader()
            HeaderInfo(
                hash = header.hash,
                number = header.number.removePrefix("0x").toULong(16),
                epoch = header.epoch,
                parentHash = header.parent_hash,
                timestamp = header.timestamp.removePrefix("0x").toULong(16)
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun estimateFee(rate: ULong): ULong {
        val feeRateHex = try {
            client.estimateFeeRate(3)
        } catch (e: Exception) {
            return rate
        }
        return try {
            feeRateHex.removePrefix("0x").toULong(16)
        } catch (e: Exception) {
            rate
        }
    }

    override suspend fun sendTransaction(transaction: Transaction): String? {
        return try {
            val txJson = json.encodeToJsonElement(Transaction.serializer(), transaction)
            client.sendTransaction(txJson)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getTransactionStatus(txHash: String): TxStatus? {
        return try {
            val response = client.getTransactionStatus(txHash)
            when (response?.tx_status?.status) {
                "pending" -> TxStatus.PENDING
                "proposed" -> TxStatus.PENDING
                "committed" -> TxStatus.CONFIRMED
                "rejected" -> TxStatus.REJECTED
                else -> TxStatus.UNKNOWN
            }
        } catch (e: Exception) {
            TxStatus.UNKNOWN
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

class ChainManager {
    private val providers = mutableListOf<ChainProvider>()
    private var activeProvider: ChainProvider? = null
    private val healthChecker = RpcHealthChecker()
    private val providerUrls = mutableMapOf<String, String>()

    fun addProvider(provider: ChainProvider, url: String = "") {
        providers.add(provider)
        providerUrls[provider.name] = url
        if (activeProvider == null) {
            activeProvider = provider
        }
    }

    fun setActiveProvider(name: String) {
        activeProvider = providers.find { it.name == name }
    }

    fun getActiveProvider(): ChainProvider? = activeProvider

    fun getAllProviders(): List<ChainProvider> = providers.toList()

    fun getHealthChecker(): RpcHealthChecker = healthChecker

    suspend fun checkProviderHealth(provider: ChainProvider): RpcHealthStatus {
        val url = providerUrls[provider.name] ?: ""
        return healthChecker.checkHealth(provider, url)
    }

    suspend fun failover(): Boolean {
        val current = activeProvider?.name ?: return false

        if (!healthChecker.shouldFailover(current)) {
            return false
        }

        val healthyProvider = healthChecker.getHealthiestProvider(
            providers.map { it to (providerUrls[it.name] ?: "") }
        )

        if (healthyProvider != null && healthyProvider.name != current) {
            activeProvider = healthyProvider
            return true
        }

        return false
    }

    suspend fun getCellsByLock(lockScript: LockScript): List<Utxo> {
        return try {
            activeProvider?.getCellsByLock(lockScript) ?: emptyList()
        } catch (e: Exception) {
            if (failover()) {
                activeProvider?.getCellsByLock(lockScript) ?: emptyList()
            } else {
                emptyList()
            }
        }
    }

    suspend fun getCellsCapacity(lockScript: LockScript): CellsCapacity? {
        return try {
            activeProvider?.getCellsCapacity(lockScript)
        } catch (e: Exception) {
            if (failover()) {
                activeProvider?.getCellsCapacity(lockScript)
            } else {
                null
            }
        }
    }

    suspend fun getTipHeader(): HeaderInfo? {
        return try {
            activeProvider?.getTipHeader()
        } catch (e: Exception) {
            if (failover()) {
                activeProvider?.getTipHeader()
            } else {
                null
            }
        }
    }

    suspend fun sendTransaction(transaction: Transaction): String? {
        return try {
            activeProvider?.sendTransaction(transaction)
        } catch (e: Exception) {
            if (failover()) {
                activeProvider?.sendTransaction(transaction)
            } else {
                null
            }
        }
    }
}
