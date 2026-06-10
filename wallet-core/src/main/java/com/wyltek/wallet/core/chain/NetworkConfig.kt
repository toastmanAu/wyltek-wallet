package com.wyltek.wallet.core.chain

import com.wyltek.wallet.core.model.NetworkType

data class NetworkConstants(
    val rpcUrl: String,
    val rpcName: String,
    val secp256k1DepGroupTxHash: String,
    val secp256k1DepGroupIndex: UInt,
    val sudtTypeCodeHash: String,
    val sudtCellDepTxHash: String,
    val sudtCellDepIndex: UInt,
    val explorerBaseUrl: String,
    val addressPrefix: String,
    val daoTypeCodeHash: String,
    val daoTypeHashType: String = "type",
    val daoCellDepTxHash: String,
    val daoCellDepIndex: UInt = 0u,
    val daoCellDepType: String = "code",
    // ML-DSA-65 PQ lock (ckb-mldsa-lock). Testnet only — set to null on networks
    // where the contract is not deployed. When null, the wallet refuses to
    // create PQ/Hybrid accounts on that network and falls back to classic.
    val mldsa65: MldsaLockConfig? = null,
)

data class MldsaLockConfig(
    val codeHash: String,
    val hashType: String,
    val cellDepTxHash: String,
    val cellDepIndex: UInt,
    val cellDepType: String,
    // CKB lock-args algorithm flag for ML-DSA-65 in ckb-mldsa-lock. Used by
    // rust-core::pq_lock_args. 60..62 maps to ckb-mldsa-sct personalization.
    val algorithmFlag: UByte = 60u,
)

object NetworkConfig {

    private val mainnet = NetworkConstants(
        rpcUrl = "https://mainnet.ckb.dev",
        rpcName = "CKB Mainnet (public)",
        secp256k1DepGroupTxHash = "0x71a7ba8fc96349fea0ed3a5c47992e3b4084b031a42264a018e0072e8172e46c",
        secp256k1DepGroupIndex = 0u,
        sudtTypeCodeHash = "0x5e7a36a77e68eecc013dfa2fe6a23f3b6c344b04005808694ae6dd45eea4cfd5",
        sudtCellDepTxHash = "0xc7813f6a415144643970c2e88e0bb6ca6a8edc5dd7c1022746f628284a7366b9",
        sudtCellDepIndex = 0u,
        explorerBaseUrl = "https://explorer.nervos.org",
        addressPrefix = "ckb",
        daoTypeCodeHash = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e",
        daoCellDepTxHash = "0xe2fb199810d49a4d8beec56718ba2593b665db9d52299a0f9e6e75416d73ff5c",
        daoCellDepIndex = 2u
    )

    private val testnet = NetworkConstants(
        rpcUrl = "https://testnet.ckbapp.dev",
        rpcName = "CKB Testnet (public)",
        secp256k1DepGroupTxHash = "0xf8de3bb47d055c46ebd0ddbd51c390d5818c9133f385013cde9c99d02f640995",
        secp256k1DepGroupIndex = 0u,
        sudtTypeCodeHash = "0xc5e5dcf215925f7ef4dfaf5f4b4f105bc321c02776d6e7d52a1db3fcd9d011a4",
        sudtCellDepTxHash = "0xe12877ebd2c3c364dc46c5c992bcfaf4fee33fa13eebdf82c591fc9825aab769",
        sudtCellDepIndex = 0u,
        explorerBaseUrl = "https://testnet.explorer.nervos.org",
        addressPrefix = "ckt",
        daoTypeCodeHash = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e",
        daoCellDepTxHash = "0x8e4966b8a2388243f421e0e8dda22f6c7b4a2e3e4e24e5f0939c9c2b946635d9",
        daoCellDepIndex = 2u,
        // PLACEHOLDER: ckb-mldsa-lock testnet deployment. Replace these four
        // values with the published deployment from
        // https://github.com/cryptape/ckb-mldsa-lock (or the team's testnet
        // deploy record). The wallet detects all-zero hashes as a placeholder
        // and refuses to broadcast PQ transactions until they're set.
        mldsa65 = MldsaLockConfig(
            codeHash = "0x0000000000000000000000000000000000000000000000000000000000000000",
            hashType = "type",
            cellDepTxHash = "0x0000000000000000000000000000000000000000000000000000000000000000",
            cellDepIndex = 0u,
            cellDepType = "code",
        ),
    )

    private val devnet = NetworkConstants(
        rpcUrl = "http://127.0.0.1:8114",
        rpcName = "CKB Devnet (local)",
        secp256k1DepGroupTxHash = "0xf8de3bb47d055c46ebd0ddbd51c390d5818c9133f385013cde9c99d02f640995",
        secp256k1DepGroupIndex = 0u,
        sudtTypeCodeHash = "0xc5e5dcf215925f7ef4dfaf5f4b4f105bc321c02776d6e7d52a1db3fcd9d011a4",
        sudtCellDepTxHash = "0xe12877ebd2c3c364dc46c5c992bcfaf4fee33fa13eebdf82c591fc9825aab769",
        sudtCellDepIndex = 0u,
        explorerBaseUrl = "http://localhost:3000",
        addressPrefix = "ckt",
        daoTypeCodeHash = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e",
        daoCellDepTxHash = "0xf8de3bb47d055c46ebd0ddbd51c390d5818c9133f385013cde9c99d02f640995",
        daoCellDepIndex = 2u
    )

    fun forNetwork(network: NetworkType): NetworkConstants = when (network) {
        NetworkType.MAINNET -> mainnet
        NetworkType.TESTNET -> testnet
        NetworkType.DEVNET -> devnet
    }

    /** True when the lock script's codeHash matches the network's ML-DSA-65 lock. */
    fun isPqLock(codeHash: String, network: NetworkType): Boolean {
        val mldsa = forNetwork(network).mldsa65 ?: return false
        if (mldsa.isPlaceholderInternal()) return false
        return codeHash.equals(mldsa.codeHash, ignoreCase = true)
    }

    private fun MldsaLockConfig.isPlaceholderInternal(): Boolean {
        val stripped = codeHash.removePrefix("0x")
        return stripped.isEmpty() || stripped.all { it == '0' }
    }
}
