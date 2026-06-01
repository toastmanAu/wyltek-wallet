package com.wyltek.wallet.core.chain

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class RpcHealthStatus(
    val name: String,
    val url: String,
    val isHealthy: Boolean,
    val latencyMs: Long,
    val lastChecked: Long,
    val tipBlockNumber: Long?,
    val errorCount: Int,
    val consecutiveFailures: Int
)

class RpcHealthChecker(
    private val checkIntervalMs: Long = 30_000L,
    private val failureThreshold: Int = 3
) {
    private val healthStatus = ConcurrentHashMap<String, RpcHealthStatus>()
    private var healthJob: Job? = null

    suspend fun checkHealth(provider: ChainProvider, url: String): RpcHealthStatus {
        val startTime = System.currentTimeMillis()
        val previous = healthStatus[provider.name]

        return try {
            val header = provider.getTipHeader()
            val latency = System.currentTimeMillis() - startTime

            val status = RpcHealthStatus(
                name = provider.name,
                url = url,
                isHealthy = header != null,
                latencyMs = latency,
                lastChecked = System.currentTimeMillis(),
                tipBlockNumber = header?.number?.toLong(),
                errorCount = if (header != null) 0 else (previous?.errorCount ?: 0) + 1,
                consecutiveFailures = if (header != null) 0 else (previous?.consecutiveFailures ?: 0) + 1
            )

            healthStatus[provider.name] = status
            status
        } catch (e: Exception) {
            val status = RpcHealthStatus(
                name = provider.name,
                url = url,
                isHealthy = false,
                latencyMs = System.currentTimeMillis() - startTime,
                lastChecked = System.currentTimeMillis(),
                tipBlockNumber = previous?.tipBlockNumber,
                errorCount = (previous?.errorCount ?: 0) + 1,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1
            )

            healthStatus[provider.name] = status
            status
        }
    }

    fun getHealthStatus(name: String): RpcHealthStatus? {
        return healthStatus[name]
    }

    fun getAllHealthStatuses(): List<RpcHealthStatus> {
        return healthStatus.values.toList()
    }

    fun shouldFailover(providerName: String): Boolean {
        val status = healthStatus[providerName] ?: return false
        return status.consecutiveFailures >= failureThreshold
    }

    fun startPeriodicCheck(
        providers: List<Pair<ChainProvider, String>>,
        scope: CoroutineScope
    ) {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (isActive) {
                providers.forEach { (provider, url) ->
                    checkHealth(provider, url)
                }
                delay(checkIntervalMs)
            }
        }
    }

    fun stopPeriodicCheck() {
        healthJob?.cancel()
        healthJob = null
    }

    fun getHealthiestProvider(
        providers: List<Pair<ChainProvider, String>>
    ): ChainProvider? {
        val healthy = providers.filter { (provider, _) ->
            val status = healthStatus[provider.name]
            status == null || status.isHealthy
        }

        return healthy.minByOrNull { (provider, _) ->
            healthStatus[provider.name]?.latencyMs ?: Long.MAX_VALUE
        }?.first
    }
}
