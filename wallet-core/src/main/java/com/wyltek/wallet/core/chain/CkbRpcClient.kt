package com.wyltek.wallet.core.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CkbRpcClient(private val url: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var nextId = 1

    private suspend fun call(method: String, params: JsonElement = buildJsonArray {}): JsonElement =
        withContext(Dispatchers.IO) {
            val id = nextId++
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }

            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw RpcException("Empty response from $method")

            if (!response.isSuccessful) {
                throw RpcException("HTTP ${response.code}: $responseBody")
            }

            val jsonResp = json.parseToJsonElement(responseBody).jsonObject

            jsonResp["error"]?.let { error ->
                val errorObj = error.jsonObject
                val code = errorObj["code"]?.jsonPrimitive?.int ?: -1
                val message = errorObj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                throw RpcException("RPC error $code: $message")
            }

            jsonResp["result"]
                ?: throw RpcException("No result in response for $method")
        }

    suspend fun getTipHeader(): HeaderResponse {
        val result = call("get_tip_header")
        return json.decodeFromJsonElement<HeaderResponse>(result)
    }

    suspend fun getCellsByLock(
        lockScript: Script,
        order: String = "asc",
        limit: String = "0x64",
        afterCursor: String? = null
    ): CellsResponse {
        val searchKey = buildJsonObject {
            put("script", json.encodeToJsonElement(Script.serializer(), lockScript))
            put("script_type", "lock")
            put("filter", buildJsonObject {
                put("script", JsonNull)
            })
        }
        val params = buildJsonArray {
            add(searchKey)
            add(json.parseToJsonElement("\"$order\""))
            add(json.parseToJsonElement("\"$limit\""))
            if (afterCursor != null) {
                add(json.parseToJsonElement("\"$afterCursor\""))
            }
        }
        val result = call("get_cells", params)
        return json.decodeFromJsonElement<CellsResponse>(result)
    }

    suspend fun getCellsCapacity(lockScript: Script): CellsCapacityResponse {
        val searchKey = buildJsonObject {
            put("script", json.encodeToJsonElement(Script.serializer(), lockScript))
            put("script_type", "lock")
        }
        val params = buildJsonArray {
            add(searchKey)
        }
        val result = call("get_cells_capacity", params)
        return json.decodeFromJsonElement<CellsCapacityResponse>(result)
    }

    suspend fun estimateFeeRate(confirmedBlocks: Int = 3): String {
        val params = buildJsonArray {
            add(json.parseToJsonElement("\"${confirmedBlocks.toHexString()}\""))
        }
        val result = call("estimate_fee_rate", params)
        return result.jsonPrimitive.content
    }

    suspend fun sendTransaction(tx: JsonElement, outputsValidator: String = "passthrough"): String {
        val params = buildJsonArray {
            add(tx)
            add(json.parseToJsonElement("\"$outputsValidator\""))
        }
        val result = call("send_transaction", params)
        return result.jsonPrimitive.content
    }

    suspend fun getTransactionStatus(txHash: String): TransactionStatusResponse? {
        val params = buildJsonArray {
            add(json.parseToJsonElement("\"$txHash\""))
        }
        return try {
            val result = call("get_transaction", params)
            json.decodeFromJsonElement<TransactionStatusResponse>(result)
        } catch (e: RpcException) {
            null
        }
    }

    suspend fun getBlockNumber(): Long {
        val header = getTipHeader()
        return header.number.toLong(16)
    }

    private fun Int.toHexString(): String = "0x${this.toString(16)}"
}

class RpcException(message: String) : Exception(message)

@Serializable
data class HeaderResponse(
    val compact_target: String = "",
    val dao: String = "",
    val epoch: String = "",
    val hash: String = "",
    val number: String = "",
    val parent_hash: String = "",
    val proposals_hash: String = "",
    val extra_hash: String = "",
    val nonce: String = "",
    val timestamp: String = "",
    val version: String = ""
)

@Serializable
data class Script(
    val code_hash: String,
    val hash_type: String,
    val args: String
)

@Serializable
data class CellOutputData(
    val data: String = ""
)

@Serializable
data class OutPoint(
    val tx_hash: String,
    val index: String
)

@Serializable
data class CellResponse(
    val output: CellOutput,
    val output_data: CellOutputData,
    val out_point: OutPoint,
    val block_number: String
)

@Serializable
data class CellOutput(
    val capacity: String,
    val lock: Script,
    val type_: Script? = null
)

@Serializable
data class CellsResponse(
    val objects: List<CellResponse>,
    val last_cursor: String
)

@Serializable
data class CellsCapacityResponse(
    val capacity: String,
    val occupied_capacity: String
)

@Serializable
data class TransactionStatusResponse(
    val tx_status: TxStatusInfo,
    val transaction: JsonElement? = null
)

@Serializable
data class TxStatusInfo(
    val status: String,
    val status_reason: String = ""
)
