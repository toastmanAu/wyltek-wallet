package com.wyltek.wallet.core.assets

import com.wyltek.wallet.core.model.*

data class AssetInfo(
    val outPoint: OutPoint,
    val type: AssetType,
    val capacity: ULong,
    val name: String?,
    val description: String?,
    val image: String?,
    val contentUrl: String?,
    val ownerLock: LockScript
)

enum class AssetType {
    SPORE,
    COTA,
    CKBFS,
    UNKNOWN
}

class AssetScanner {

    suspend fun scanSporeCells(ownerLock: LockScript): List<AssetInfo> {
        TODO("Implement Spore cell scanner")
    }

    suspend fun scanCotaCells(ownerLock: LockScript): List<AssetInfo> {
        TODO("Implement CoTA cell scanner")
    }

    suspend fun scanCkbfsCells(ownerLock: LockScript): List<AssetInfo> {
        TODO("Implement CKBFS cell scanner")
    }

    suspend fun scanAll(ownerLock: LockScript): List<AssetInfo> {
        val spore = scanSporeCells(ownerLock)
        val cota = scanCotaCells(ownerLock)
        val ckbfs = scanCkbfsCells(ownerLock)
        return spore + cota + ckbfs
    }

    suspend fun getAssetDetail(outPoint: OutPoint): AssetInfo? {
        TODO("Implement asset detail fetch")
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
