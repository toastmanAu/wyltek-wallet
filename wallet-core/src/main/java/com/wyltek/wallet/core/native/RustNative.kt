package com.wyltek.wallet.core.native

/**
 * Kotlin wrappers for Rust UniFFI functions.
 * Provides clean API surface for wallet-core module.
 */
object RustNative {

    /**
     * Get wallet version string
     */
    fun walletVersion(): String {
        return com.wyltek.wallet.core.native.walletVersion()
    }

    /**
     * Decode a CKB bech32m address
     */
    fun decodeAddress(address: String): AddressInfo {
        return com.wyltek.wallet.core.native.decodeAddress(address)
    }

    /**
     * Encode a CKB address from components
     */
    fun encodeAddress(
        codeHash: String,
        hashType: String,
        args: String,
        network: String
    ): String {
        return com.wyltek.wallet.core.native.encodeAddress(codeHash, hashType, args, network)
    }

    /**
     * Validate a CKB address
     */
    fun validateAddress(address: String): Boolean {
        return com.wyltek.wallet.core.native.validateAddress(address)
    }

    /**
     * BLAKE2b-256 hash
     */
    fun blake2b256(dataHex: String): String {
        return com.wyltek.wallet.core.native.blake2b256(dataHex)
    }

    /**
     * CKB hash (personalization)
     */
    fun ckbHash(dataHex: String): String {
        return com.wyltek.wallet.core.native.ckbHash(dataHex)
    }

    /**
     * Generate secp256k1 keypair from seed
     */
    fun generateSecp256k1Keypair(seedHex: String, derivationPath: String): KeyPair {
        return com.wyltek.wallet.core.native.generateSecp256k1Keypair(seedHex, derivationPath)
    }

    /**
     * Convert public key to CKB address
     */
    fun publicKeyToCkbAddress(publicKeyHex: String, network: String): String {
        return com.wyltek.wallet.core.native.publicKeyToCkbAddress(publicKeyHex, network)
    }

    /**
     * Convert public key to PQ address
     */
    fun publicKeyToPqAddress(publicKeyHex: String, network: String, algorithmId: UByte): String {
        return com.wyltek.wallet.core.native.publicKeyToPqAddress(publicKeyHex, network, algorithmId)
    }

    /**
     * Generate mnemonic phrase
     */
    fun generateMnemonic(wordCount: UInt): MnemonicResult {
        return com.wyltek.wallet.core.native.generateMnemonic(wordCount)
    }

    /**
     * Convert mnemonic to seed
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String): String {
        return com.wyltek.wallet.core.native.mnemonicToSeed(mnemonic, passphrase)
    }

    /**
     * Validate mnemonic phrase
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        return com.wyltek.wallet.core.native.validateMnemonic(mnemonic)
    }

    /**
     * Generate ML-DSA-65 keypair
     */
    fun generateMldsa65Keypair(): PqKeyPair {
        return com.wyltek.wallet.core.native.generateMldsa65Keypair()
    }

    /**
     * Generate ML-DSA-65 keypair from seed
     */
    fun mldsa65FromSeed(seedHex: String): PqKeyPair {
        return com.wyltek.wallet.core.native.mldsa65FromSeed(seedHex)
    }

    /**
     * Sign with ML-DSA-65
     */
    fun mldsa65Sign(messageHex: String, privateKeyHex: String): String {
        return com.wyltek.wallet.core.native.mldsa65Sign(messageHex, privateKeyHex)
    }

    /**
     * Verify ML-DSA-65 signature
     */
    fun mldsa65Verify(messageHex: String, signatureHex: String, publicKeyHex: String): Boolean {
        return com.wyltek.wallet.core.native.mldsa65Verify(messageHex, signatureHex, publicKeyHex)
    }

    /**
     * Generate PQ lock args
     */
    fun pqLockArgs(publicKeyHex: String, algorithmId: UByte): String {
        return com.wyltek.wallet.core.native.pqLockArgs(publicKeyHex, algorithmId)
    }

    /**
     * Sign message with specified algorithm
     */
    fun signMessage(messageHex: String, privateKeyHex: String, algorithm: String): String {
        return com.wyltek.wallet.core.native.signMessage(messageHex, privateKeyHex, algorithm)
    }

    /**
     * Sign transaction with specified algorithm
     */
    fun signTransaction(rawTxHex: String, privateKeyHex: String, algorithm: String): String {
        return com.wyltek.wallet.core.native.signTransaction(rawTxHex, privateKeyHex, algorithm)
    }

    /**
     * Verify signature with specified algorithm
     */
    fun verifySignature(
        messageHex: String,
        signatureHex: String,
        publicKeyHex: String,
        algorithm: String
    ): Boolean {
        return com.wyltek.wallet.core.native.verifySignature(messageHex, signatureHex, publicKeyHex, algorithm)
    }

    /**
     * Build transaction
     */
    fun buildTransaction(request: TransactionRequest): BuiltTransaction {
        return com.wyltek.wallet.core.native.buildTransaction(request)
    }
}
