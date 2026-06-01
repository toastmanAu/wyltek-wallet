package com.wyltek.wallet.core.signing

import com.wyltek.wallet.core.model.*

interface KeyStorage {
    suspend fun storeKey(keyId: String, keyData: ByteArray, auth: AuthMethod)
    suspend fun loadKey(keyId: String, auth: AuthMethod): ByteArray?
    suspend fun deleteKey(keyId: String, auth: AuthMethod): Boolean
    suspend fun listKeys(): List<String>
}

sealed class AuthMethod {
    object None : AuthMethod()
    data class Password(val value: String) : AuthMethod()
    data class Biometric(val cryptoObject: Any?) : AuthMethod()
    data class Passkey(val credentialId: String) : AuthMethod()
}

interface TransactionSigner {
    suspend fun signTransaction(
        transaction: Transaction,
        keyId: String,
        auth: AuthMethod
    ): Transaction

    suspend fun signMessage(
        message: ByteArray,
        keyId: String,
        auth: AuthMethod
    ): ByteArray
}

class AndroidKeystoreStorage : KeyStorage {

    override suspend fun storeKey(keyId: String, keyData: ByteArray, auth: AuthMethod) {
        TODO("Implement Android Keystore key wrapping")
    }

    override suspend fun loadKey(keyId: String, auth: AuthMethod): ByteArray? {
        TODO("Implement Android Keystore key unwrapping")
    }

    override suspend fun deleteKey(keyId: String, auth: AuthMethod): Boolean {
        TODO("Implement Android Keystore key deletion")
    }

    override suspend fun listKeys(): List<String> {
        TODO("Implement Android Keystore key listing")
    }
}

class LocalSigner(
    private val keyStorage: KeyStorage
) : TransactionSigner {

    override suspend fun signTransaction(
        transaction: Transaction,
        keyId: String,
        auth: AuthMethod
    ): Transaction {
        val keyData = keyStorage.loadKey(keyId, auth)
            ?: throw SecurityException("Failed to load signing key")

        val signedWitnesses = transaction.witnesses.map { witness ->
            // TODO: Implement actual signing via Rust core
            // For now, return placeholder
            "0x"
        }

        return transaction.copy(witnesses = signedWitnesses)
    }

    override suspend fun signMessage(
        message: ByteArray,
        keyId: String,
        auth: AuthMethod
    ): ByteArray {
        val keyData = keyStorage.loadKey(keyId, auth)
            ?: throw SecurityException("Failed to load signing key")

        // TODO: Implement actual signing via Rust core
        return ByteArray(64)
    }
}
