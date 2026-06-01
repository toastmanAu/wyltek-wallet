package com.wyltek.wallet.core.messaging

import com.wyltek.wallet.core.model.*

data class Message(
    val id: String,
    val from: String,
    val to: String,
    val content: ByteArray,
    val timestamp: ULong,
    val encrypted: Boolean = true
)

data class ContactProfile(
    val address: String,
    val name: String?,
    val publicKey: ByteArray?,
    val discovered: Boolean = false
)

class MessagingService {

    suspend fun createProfileCell(
        ownerAddress: String,
        name: String
    ): String? {
        TODO("Implement CEMP-PQ profile cell creation")
    }

    suspend fun discoverProfile(address: String): ContactProfile? {
        TODO("Implement contact profile discovery")
    }

    suspend fun encryptMessage(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray
    ): ByteArray {
        TODO("Implement ML-KEM + AES-256-GCM encryption")
    }

    suspend fun decryptMessage(
        ciphertext: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        TODO("Implement ML-KEM + AES-256-GCM decryption")
    }

    suspend fun sendMessage(
        from: String,
        to: String,
        content: ByteArray
    ): String? {
        TODO("Implement message cell creation and broadcast")
    }

    suspend fun scanNotifications(
        ownerAddress: String
    ): List<Message> {
        TODO("Implement notification cell scanner")
    }
}
