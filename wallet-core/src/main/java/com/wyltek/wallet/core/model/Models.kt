package com.wyltek.wallet.core.model

import kotlinx.serialization.Serializable

enum class AccountType {
    CLASSIC,
    POST_QUANTUM,
    HYBRID
}

enum class LockAlgorithm {
    SECP256K1_BLAKE160,
    ML_DSA_44,
    ML_DSA_65,
    ML_DSA_87,
    FALCON_512,
    FALCON_1024
}

enum class NetworkType {
    MAINNET,
    TESTNET,
    DEVNET
}

@Serializable
data class WalletAccount(
    val id: String,
    val name: String,
    val type: AccountType,
    val network: NetworkType,
    val addresses: List<CkbAddress>,
    val createdAt: Long,
    val isHD: Boolean = true
)

@Serializable
data class CkbAddress(
    val bech32m: String,
    val lockScript: LockScript,
    val network: NetworkType,
    val formatVersion: AddressFormatVersion
)

@Serializable
data class LockScript(
    val codeHash: String,
    val hashType: String,
    val args: String
)

@Serializable
data class CellOutput(
    val capacity: ULong,
    val lock: LockScript,
    val type_: LockScript? = null
)

@Serializable
data class OutPoint(
    val txHash: String,
    val index: UInt
)

@Serializable
data class CellInput(
    val previousOutput: OutPoint,
    val since: ULong = 0u
)

@Serializable
data class Transaction(
    val version: UInt,
    val cellDeps: List<CellDep>,
    val headerDeps: List<String>,
    val inputs: List<CellInput>,
    val outputs: List<CellOutput>,
    val outputsData: List<String>,
    val witnesses: List<String>
)

@Serializable
data class CellDep(
    val outPoint: OutPoint,
    val depType: DepType
)

@Serializable
enum class DepType {
    CODE,
    GROUP
}

@Serializable
enum class AddressFormatVersion {
    CKB2019,
    CKB2021,
    DEPRECATEDShort,
    DEPRECATEDFull,
    UNKNOWN
}

@Serializable
data class Utxo(
    val outPoint: OutPoint,
    val capacity: ULong,
    val lock: LockScript,
    val type_: LockScript? = null,
    val data: String? = null,
    val blockNumber: ULong = 0u
)

@Serializable
data class Balance(
    val totalCapacity: ULong,
    val occupiedCapacity: ULong,
    val availableCapacity: ULong,
    val cells: List<Utxo>
)
