package com.wyltek.wallet.core.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

class CkbRpcClient(private val url: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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

            val response = try {
                Log.d("CkbRpc", "Calling $method on $url")
                client.newCall(request).execute()
            } catch (e: java.io.IOException) {
                Log.e("CkbRpc", "Network error calling $method: ${e.javaClass.simpleName}: ${e.message}")
                throw RpcException("Network error: ${e.javaClass.simpleName} - ${e.message}")
            }

            val responseBody = response.body?.string()
                ?: throw RpcException("Empty response from $method")

            if (!response.isSuccessful) {
                Log.e("CkbRpc", "HTTP ${response.code} for $method: $responseBody")
                throw RpcException("HTTP ${response.code}: $responseBody")
            }

            Log.d("CkbRpc", "$method succeeded")
            if (method == "get_cells") {
                Log.d("CkbRpc", "get_cells response (first 500): ${responseBody.take(500)}")
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

    /** Register lock scripts for the light client to sync from `blockNumber`. */
    suspend fun setScripts(scripts: List<ScriptStatus>) {
        val params = buildJsonArray {
            add(json.encodeToJsonElement(ListSerializer(ScriptStatus.serializer()), scripts))
        }
        call("set_scripts", params)
    }

    /** The scripts the light client is currently watching. */
    suspend fun getScripts(): List<ScriptStatus> {
        val result = call("get_scripts")
        return json.decodeFromJsonElement(ListSerializer(ScriptStatus.serializer()), result)
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
        val response = json.decodeFromJsonElement<CellsResponse>(result)
        Log.d("CkbRpc", "getCellsByLock returned ${response.objects.size} cells, lastCursor=${response.last_cursor}")
        return response
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

    suspend fun getCellsByLockAndType(
        lockScript: Script,
        typeScript: Script,
        order: String = "asc",
        limit: String = "0x64",
        afterCursor: String? = null
    ): CellsResponse {
        val searchKey = buildJsonObject {
            put("script", json.encodeToJsonElement(Script.serializer(), lockScript))
            put("script_type", "lock")
            put("filter", buildJsonObject {
                put("script", json.encodeToJsonElement(Script.serializer(), typeScript))
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

    suspend fun getTransactionsByLock(
        lockScript: Script,
        order: String = "desc",
        limit: String = "0x64",
        afterCursor: String? = null
    ): TransactionsResponse {
        val searchKey = buildJsonObject {
            put("script", json.encodeToJsonElement(Script.serializer(), lockScript))
            put("script_type", "lock")
            put("group_by_transaction", true)
        }
        val params = buildJsonArray {
            add(searchKey)
            add(json.parseToJsonElement("\"$order\""))
            add(json.parseToJsonElement("\"$limit\""))
            if (afterCursor != null) {
                add(json.parseToJsonElement("\"$afterCursor\""))
            }
        }
        val result = call("get_transactions", params)
        return json.decodeFromJsonElement<TransactionsResponse>(result)
    }

    suspend fun getBlockNumber(): Long {
        val header = getTipHeader()
        return header.number.toLong(16)
    }

    suspend fun getHeaderByNumber(blockNumber: String): HeaderResponse {
        val params = buildJsonArray {
            add(json.parseToJsonElement("\"$blockNumber\""))
        }
        val result = call("get_header_by_number", params)
        return json.decodeFromJsonElement<HeaderResponse>(result)
    }

    suspend fun getTransaction(txHash: String): TransactionDetailResponse? {
        val params = buildJsonArray {
            add(json.parseToJsonElement("\"$txHash\""))
            add(json.parseToJsonElement("\"0x2\""))
        }
        return try {
            val result = call("get_transaction", params)
            json.decodeFromJsonElement<TransactionDetailResponse>(result)
        } catch (e: RpcException) {
            null
        }
    }

    /**
     * NervosDAO maximum withdraw (deposit + accrued compensation) for a deposit
     * cell, evaluated as of [withdrawBlockHash]. Mirrors the proven harness call
     * `calculate_dao_maximum_withdraw([{tx_hash, index}, withdraw_block_hash])`.
     * Returns the hex shannon string (e.g. "0x...") or null on error.
     */
    suspend fun calculateDaoMaximumWithdraw(
        depositTxHash: String,
        depositIndex: String,
        withdrawBlockHash: String
    ): String? {
        val params = buildJsonArray {
            add(buildJsonObject {
                put("tx_hash", depositTxHash)
                put("index", depositIndex)
            })
            add(json.parseToJsonElement("\"$withdrawBlockHash\""))
        }
        return try {
            call("calculate_dao_maximum_withdraw", params).jsonPrimitive.content
        } catch (e: Exception) {
            Log.e("CkbRpc", "calculateDaoMaximumWithdraw failed: ${e.message}")
            null
        }
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

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ScriptStatus(
    val script: Script,
    // Must always serialize even though it equals its default — kotlinx omits
    // defaults otherwise, and the set_scripts RPC requires script_type present.
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    @SerialName("script_type") val scriptType: String = "lock",
    @SerialName("block_number") val blockNumber: String
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
    @Serializable(CellOutputDataSerializer::class)
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

object CellOutputDataSerializer : KSerializer<CellOutputData> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("CellOutputData")

    override fun deserialize(decoder: Decoder): CellOutputData {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: throw SerializationException("Expected JSON decoder")
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> CellOutputData(data = element.content)
            is JsonObject -> {
                val data = element["data"]?.jsonPrimitive?.content ?: ""
                CellOutputData(data = data)
            }
            else -> CellOutputData(data = "")
        }
    }

    override fun serialize(encoder: Encoder, value: CellOutputData) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw SerializationException("Expected JSON encoder")
        jsonEncoder.encodeString(value.data)
    }
}

@Serializable
data class CellsResponse(
    val objects: List<CellResponse>,
    val last_cursor: String
)

@Serializable
data class CellsCapacityResponse(
    val capacity: String,
    val occupied_capacity: String? = null
)

@Serializable
data class TransactionStatusResponse(
    val tx_status: TxStatusInfo,
    val transaction: JsonElement? = null
)

@Serializable
data class TxStatusInfo(
    val status: String,
    val status_reason: String = "",
    val block_hash: String? = null,
    val block_number: String? = null
)

@Serializable
data class TransactionResponse(
    val tx_hash: String,
    val block_number: String = "",
    val tx_index: String = "",
    val io_index: String = "",
    val io_type: String = ""
)

@Serializable
data class TransactionsResponse(
    val objects: List<TransactionResponse>,
    val last_cursor: String
)

@Serializable
data class TransactionDetailResponse(
    val transaction: TransactionDetailTx? = null,
    val tx_status: TxStatusInfo
)

@Serializable
data class TransactionDetailTx(
    val version: String = "0x0",
    val cell_deps: List<CellDepInfo> = listOf(),
    val header_deps: List<String> = listOf(),
    val inputs: List<TxInput> = listOf(),
    val outputs: List<CellOutput> = listOf(),
    val outputs_data: List<String> = listOf(),
    val witnesses: List<String> = listOf()
)

@Serializable
data class CellDepInfo(
    val out_point: OutPoint,
    val dep_type: String = "code"
)

@Serializable
data class TxInput(
    val previous_output: OutPoint,
    val since: String = "0x0"
)
