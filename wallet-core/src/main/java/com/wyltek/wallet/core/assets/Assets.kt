package com.wyltek.wallet.core.assets

import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.CkbRpcClient
import com.wyltek.wallet.core.chain.RpcProfile
import com.wyltek.wallet.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.util.UUID

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
    SUDT,
    UNKNOWN
}

data class TokenInfo(
    val typeScript: LockScript,
    val amount: BigInteger,
    val symbol: String = "sUDT"
)

data class Listing(
    val id: String,
    val asset: AssetInfo,
    val price: ULong,
    val royaltyPercent: UInt,
    val sellerLock: LockScript,
    val expiryBlock: ULong?,
    val createdAt: Long,
    val status: ListingStatus
)

enum class ListingStatus {
    ACTIVE,
    SOLD,
    CANCELLED,
    EXPIRED
}

class ListingService(private val chainManager: ChainManager) {

    private val listings = mutableMapOf<String, Listing>()

    suspend fun listForSale(
        asset: AssetInfo,
        price: ULong,
        royaltyPercent: UInt,
        expiryBlock: ULong?
    ): Listing? {
        val listingId = UUID.randomUUID().toString()

        val listing = Listing(
            id = listingId,
            asset = asset,
            price = price,
            royaltyPercent = royaltyPercent,
            sellerLock = asset.ownerLock,
            expiryBlock = expiryBlock,
            createdAt = System.currentTimeMillis(),
            status = ListingStatus.ACTIVE
        )

        listings[listingId] = listing
        return listing
    }

    suspend fun cancelListing(listingId: String): Boolean {
        val listing = listings[listingId] ?: return false
        listings[listingId] = listing.copy(status = ListingStatus.CANCELLED)
        return true
    }

    suspend fun buyAsset(
        listingId: String,
        buyerLock: LockScript,
        paymentUtxos: List<Utxo>
    ): Transaction? {
        val listing = listings[listingId] ?: return null
        if (listing.status != ListingStatus.ACTIVE) return null

        val totalPayment = paymentUtxos.sumOf { it.capacity }
        if (totalPayment < listing.price) return null

        val royaltyAmount = listing.price * listing.royaltyPercent.toULong() / 100uL
        val sellerAmount = listing.price - royaltyAmount

        listings[listingId] = listing.copy(status = ListingStatus.SOLD)

        return Transaction(
            version = 0u,
            cellDeps = emptyList(),
            headerDeps = emptyList(),
            inputs = paymentUtxos.map { utxo ->
                CellInput(
                    previousOutput = OutPoint(
                        txHash = utxo.outPoint.txHash,
                        index = utxo.outPoint.index
                    ),
                    since = 0u
                )
            },
            outputs = listOf(
                CellOutput(
                    capacity = sellerAmount,
                    lock = listing.sellerLock
                ),
                CellOutput(
                    capacity = listing.asset.capacity,
                    lock = buyerLock
                )
            ),
            outputsData = listOf("0x", "0x"),
            witnesses = emptyList()
        )
    }

    fun getActiveListings(): List<Listing> {
        return listings.values.filter { it.status == ListingStatus.ACTIVE }
    }

    fun getListingsBySeller(sellerLock: LockScript): List<Listing> {
        return listings.values.filter {
            it.sellerLock == sellerLock && it.status == ListingStatus.ACTIVE
        }
    }

    fun getListing(listingId: String): Listing? {
        return listings[listingId]
    }
}

class AssetScanner(private val chainManager: ChainManager) {

    companion object {
        private const val SPORE_TYPE_CODE_HASH = "0x25c29c62f4984899f74328a2786bfb126ef0eee9b2e1c020614c417bc3a2d838"
        private const val CKBFS_TYPE_CODE_HASH = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4bfff5a105f828c0b50aa673"
        private const val COTA_SMT_TYPE_HASH = "0x86a18e3e05d03f80ca2d3fc1e06b0a5424070009e26a46d7dad0e6ca74f47e55"
        private const val SUDT_TYPE_CODE_HASH = "0xc5e5dcf215925f7ef4dfaf5f4b4f105bc321c02776d6e7d52a1db3fcd9d011a4"

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

    suspend fun scanSudtCells(
        ownerLock: LockScript,
        customTokens: List<CustomTokenDefinition> = emptyList(),
        network: com.wyltek.wallet.core.model.NetworkType = com.wyltek.wallet.core.model.NetworkType.TESTNET
    ): List<TokenInfo> {
        val cells = try {
            chainManager.getCellsByLock(ownerLock)
        } catch (e: Exception) {
            return emptyList()
        }

        val networkConfig = com.wyltek.wallet.core.chain.NetworkConfig.forNetwork(network)

        // Known SUDT + custom tokens
        val knownCodeHashes = mutableSetOf(networkConfig.sudtTypeCodeHash.lowercase())
        val customByCodeHash = customTokens.groupBy { it.codeHash.lowercase() }
        knownCodeHashes.addAll(customByCodeHash.keys)

        val sudtCells = cells.filter { cell ->
            val typeScript = cell.type_ ?: return@filter false
            knownCodeHashes.contains(typeScript.codeHash.lowercase()) &&
                    typeScript.hashType == "type"
        }

        // Group by type script args (identifies specific sUDT)
        val grouped = sudtCells.groupBy { it.type_!!.args }

        return grouped.map { (args, cellsOfType) ->
            val totalAmount = cellsOfType.fold(BigInteger.ZERO) { acc, cell ->
                val data = cell.data?.removePrefix("0x") ?: ""
                val amount = if (data.length >= 32) {
                    decodeU128Le(data)
                } else BigInteger.ZERO
                acc + amount
            }

            // Find custom token symbol if matched
            val custom = customTokens.find { it.args.equals(args, ignoreCase = true) }
            TokenInfo(
                typeScript = cellsOfType.first().type_!!,
                amount = totalAmount,
                symbol = custom?.symbol ?: "sUDT"
            )
        }
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

    private fun decodeU128Le(hexData: String): BigInteger {
        val bytes = hexToBytes(hexData).take(16).reversed().toByteArray()
        return BigInteger(1, bytes)
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
