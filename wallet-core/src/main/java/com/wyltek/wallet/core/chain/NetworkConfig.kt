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

    // CKB built-in Type ID system script — same code_hash on every network.
    const val TYPE_ID_CODE_HASH = "0x00000000000000000000000000000000000000000000000000545950455f4944"
    const val TYPE_ID_HASH_TYPE = "type"

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
        // Testnet genesis secp256k1_blake160_sighash_all dep group (Lumos
        // AGGRON4). The prior value (…c46ebd0…f640995) does not exist on Pudge
        // — classic sends failed to resolve the dep. Verified 2026-06-15.
        secp256k1DepGroupTxHash = "0xf8de3bb47d055cdf460d93a2a6e1b05f7432f9777c8c474abf4eec1d4aee5d37",
        secp256k1DepGroupIndex = 0u,
        sudtTypeCodeHash = "0xc5e5dcf215925f7ef4dfaf5f4b4f105bc321c02776d6e7d52a1db3fcd9d011a4",
        sudtCellDepTxHash = "0xe12877ebd2c3c364dc46c5c992bcfaf4fee33fa13eebdf82c591fc9825aab769",
        sudtCellDepIndex = 0u,
        explorerBaseUrl = "https://testnet.explorer.nervos.org",
        addressPrefix = "ckt",
        daoTypeCodeHash = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e",
        // Testnet Nervos DAO dep = genesis tx[0] @ index 2 (Lumos AGGRON4). The
        // prior value (0x8e4966b8…) does not exist on Pudge. Verified 2026-06-15.
        daoCellDepTxHash = "0x8f8c79eb6671709633fe6a46de93c0fedc9c1b8a6527a18d3983879542635c9f",
        daoCellDepIndex = 2u,
        // ckb-mldsa-lock testnet deployment: mldsa65-lock-v2-rust (the live,
        // supported contract). code_hash is the Script hash (hash_type "type",
        // stable across type_id upgrades). cellDep points at the session-10
        // deploy tx; the mldsa65-lock-v2-rust binary sits at output index 3.
        // Verified on-chain 2026-06-14 (spend tx 0x51ccf4cf…601e).
        //
        // NOTE: the legacy C lock 0x8984f4…d310d (the bundled JS SDK's target)
        // is DEPRECATED — sighash coverage gap, lost owner — do not use.
        // See contracts/mldsa-lock-v2-rust in toastmanAu/ckb-mldsa-lock.
        mldsa65 = MldsaLockConfig(
            codeHash = "0xd70653f7fd51e173ec506b76081f37bf4acebb8a15dc79e6d4ad43ca4d3b78a4",
            hashType = "type",
            cellDepTxHash = "0x1074b1ac79213c22b5e32a0fde44a858a47f9575c9f54006a1deb80d32070cb1",
            cellDepIndex = 3u,
            cellDepType = "code",
            algorithmFlag = 61u, // ML-DSA-65 param id
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
