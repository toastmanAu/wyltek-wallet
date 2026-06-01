package com.wyltek.wallet.core.assets

import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.CkbRpcClient
import com.wyltek.wallet.core.chain.RpcProfile
import com.wyltek.wallet.core.model.*
import kotlinx.serialization.json.*

data class AssetInfo(
    val outPoint: OutPoint,
    val type: AssetType,
    val capacity: ULong,
    val name: String?,
    val description: String?,
    val image: String?,
    val contentUrl: String?,
    val contentType: String?,
    val ownerLock: LockScript,
    val clusterId: String? = null,
    val rawContent: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetInfo) return false
        return outPoint == other.outPoint
    }
    override fun hashCode(): Int = outPoint.hashCode()
}

enum class AssetType {
    SPORE,
    COTA,
    CKBFS,
    UNKNOWN
}

class AssetScanner(private val chainManager: ChainManager) {

    companion object {
        private const val SPORE_TYPE_CODE_HASH = "0x25c29c62f4984899f74328a2786bfb126ef0eee9b2e1c020614c417bc3a2d838"
        private const val CKBFS_TYPE_CODE_HASH = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4bfff5a105f828c0b50aa673"
        private const val COTA_SMT_TYPE_HASH = "0x86a18e3e05d03f80ca2d3fc1e06b0a5424070009e26a46d7dad0e6ca74f47e55"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    suspend fun scanSporeCells(ownerLock: LockScript): List<AssetInfo> {
        val cells = try {
            chainManager.getCellsByLock(ownerLock)
        } catch (e: Exception) {
            return emptyList()
        }

        return cells.mapNotNull { cell ->
            val typeScript = cell.type_ ?: return@mapNotNull null
            if (!typeScript.codeHash.equals(SPORE_TYPE_CODE_HASH, ignoreCase = true)) {
                return@mapNotNull null
            }

            val cellData = cell.data?.removePrefix("0x") ?: return@mapNotNull null
            if (cellData.isEmpty()) return@mapNotNull null

            try {
                val data = hexToBytes(cellData)
                val sporeData = decodeSporeData(data)

                AssetInfo(
                    outPoint = cell.outPoint,
                    type = AssetType.SPORE,
                    capacity = cell.capacity,
                    name = sporeData["content_type"]?.let { String(hexToBytes(it)) },
                    description = null,
                    image = null,
                    contentUrl = sporeData["content"]?.let { "spore://$it" },
                    contentType = sporeData["content_type"]?.let { String(hexToBytes(it)) },
                    ownerLock = cell.lock,
                    clusterId = sporeData["cluster_id"],
                    rawContent = sporeData["content"]?.let { hexToBytes(it) }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun scanCotaCells(ownerLock: LockScript): List<AssetInfo> {
        val cells = try {
            chainManager.getCellsByLock(ownerLock)
        } catch (e: Exception) {
            return emptyList()
        }

        return cells.mapNotNull { cell ->
            val typeScript = cell.type_ ?: return@mapNotNull null
            if (!typeScript.codeHash.equals(COTA_SMT_TYPE_HASH, ignoreCase = true)) {
                return@mapNotNull null
            }

            val cellData = cell.data?.removePrefix("0x") ?: return@mapNotNull null
            if (cellData.isEmpty()) return@mapNotNull null

            try {
                val cotaData = decodeCoTAData(cellData)

                AssetInfo(
                    outPoint = cell.outPoint,
                    type = AssetType.COTA,
                    capacity = cell.capacity,
                    name = cotaData["name"],
                    description = cotaData["description"],
                    image = cotaData["image"],
                    contentUrl = cotaData["content_url"],
                    contentType = cotaData["content_type"],
                    ownerLock = cell.lock
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun scanCkbfsCells(ownerLock: LockScript): List<AssetInfo> {
        val cells = try {
            chainManager.getCellsByLock(ownerLock)
        } catch (e: Exception) {
            return emptyList()
        }

        return cells.mapNotNull { cell ->
            val typeScript = cell.type_ ?: return@mapNotNull null
            if (!typeScript.codeHash.equals(CKBFS_TYPE_CODE_HASH, ignoreCase = true)) {
                return@mapNotNull null
            }

            val cellData = cell.data?.removePrefix("0x") ?: return@mapNotNull null
            if (cellData.isEmpty()) return@mapNotNull null

            try {
                val ckbfsData = decodeCKBFSData(cellData, typeScript.args)

                AssetInfo(
                    outPoint = cell.outPoint,
                    type = AssetType.CKBFS,
                    capacity = cell.capacity,
                    name = ckbfsData["filename"],
                    description = null,
                    image = if (ckbfsData["content_type"]?.startsWith("image/") == true) {
                        "ckbfs://${typeScript.args}"
                    } else null,
                    contentUrl = "ckbfs://${typeScript.args}",
                    contentType = ckbfsData["content_type"],
                    ownerLock = cell.lock
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun scanAll(ownerLock: LockScript): List<AssetInfo> {
        val spore = scanSporeCells(ownerLock)
        val cota = scanCotaCells(ownerLock)
        val ckbfs = scanCkbfsCells(ownerLock)
        return spore + cota + ckbfs
    }

    suspend fun getAssetDetail(outPoint: OutPoint): AssetInfo? {
        return null
    }

    private fun decodeSporeData(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 0

        if (data.size < 2) return result

        val fieldCount = data[offset].toInt() and 0xFF
        offset++

        for (i in 0 until minOf(fieldCount, 3)) {
            if (offset >= data.size) break
            val fieldLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2

            if (offset + fieldLen > data.size) break
            val fieldData = data.copyOfRange(offset, offset + fieldLen)
            offset += fieldLen

            when (i) {
                0 -> result["cluster_id"] = bytesToHex(fieldData)
                1 -> result["content_type"] = bytesToHex(fieldData)
                2 -> result["content"] = bytesToHex(fieldData)
            }
        }

        return result
    }

    private fun decodeCoTAData(hexData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val data = hexToBytes(hexData)

        if (data.size < 44) return result

        val keyLen = 20
        val key = data.copyOfRange(0, keyLen)
        result["key"] = bytesToHex(key)

        if (data.size > 44) {
            val smtRoot = data.copyOfRange(20, 52)
            result["smt_root"] = bytesToHex(smtRoot)
        }

        return result
    }

    private fun decodeCKBFSData(hexData: String, typeArgs: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val data = hexToBytes(hexData)

        if (data.isEmpty()) return result

        if (data.size > 32) {
            val metadata = String(data.copyOfRange(0, minOf(128, data.size)))
            val lines = metadata.split("\n")
            for (line in lines) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    result[parts[0].trim().lowercase()] = parts[1].trim()
                }
            }
        }

        result["hash"] = typeArgs
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x")
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toByte(16)
        }
        return bytes
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class ListingService {

    suspend fun listForSale(
        asset: AssetInfo,
        price: ULong,
        royaltyPercent: UInt,
        expiryBlock: ULong?
    ): String? {
        TODO("Implement LSDL listing")
    }

    suspend fun cancelListing(outPoint: OutPoint): Boolean {
        TODO("Implement LSDL delisting")
    }

    suspend fun buyAsset(
        listingOutPoint: OutPoint,
        paymentUtxos: List<Utxo>
    ): Transaction? {
        TODO("Implement LSDL purchase")
    }
}
