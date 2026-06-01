package com.wyltek.wallet.core.messaging

import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Message(
    val id: String,
    val from: String,
    val to: String,
    val content: ByteArray,
    val timestamp: ULong,
    val encrypted: Boolean = true,
    val read: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

data class ContactProfile(
    val address: String,
    val name: String?,
    val publicKey: ByteArray?,
    val discovered: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactProfile) return false
        return address == other.address
    }
    override fun hashCode(): Int = address.hashCode()
}

data class Conversation(
    val contactAddress: String,
    val contactName: String?,
    val messages: List<Message>,
    val lastMessage: Message?,
    val unreadCount: Int
)

class MessagingService {

    private val messages = mutableMapOf<String, MutableList<Message>>()
    private val contacts = mutableMapOf<String, ContactProfile>()

    suspend fun createProfileCell(
        ownerAddress: String,
        name: String
    ): String? {
        return UUID.randomUUID().toString()
    }

    suspend fun discoverProfile(address: String): ContactProfile? {
        return contacts[address] ?: ContactProfile(
            address = address,
            name = null,
            publicKey = null,
            discovered = false
        )
    }

    suspend fun encryptMessage(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray
    ): ByteArray {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)

        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val encrypted = cipher.doFinal(plaintext)
        return iv + encrypted
    }

    suspend fun decryptMessage(
        ciphertext: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        if (ciphertext.size < 28) throw Exception("Ciphertext too short")

        val iv = ciphertext.copyOfRange(0, 12)
        val encrypted = ciphertext.copyOfRange(12, ciphertext.size)

        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)

        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(encrypted)
    }

    suspend fun sendMessage(
        from: String,
        to: String,
        content: ByteArray
    ): Message {
        val message = Message(
            id = UUID.randomUUID().toString(),
            from = from,
            to = to,
            content = content,
            timestamp = System.currentTimeMillis().toULong(),
            encrypted = true,
            read = false
        )

        messages.getOrPut(to) { mutableListOf() }.add(message)
        messages.getOrPut(from) { mutableListOf() }.add(message)

        return message
    }

    suspend fun scanNotifications(
        ownerAddress: String
    ): List<Message> {
        return messages[ownerAddress]?.filter { it.to == ownerAddress && !it.read } ?: emptyList()
    }

    fun addContact(contact: ContactProfile) {
        contacts[contact.address] = contact
    }

    fun getContact(address: String): ContactProfile? {
        return contacts[address]
    }

    fun getAllContacts(): List<ContactProfile> {
        return contacts.values.toList()
    }

    fun getConversations(ownerAddress: String): List<Conversation> {
        val ownerMessages = messages[ownerAddress] ?: return emptyList()

        val conversations = ownerMessages
            .groupBy { msg ->
                if (msg.from == ownerAddress) msg.to else msg.from
            }
            .map { (address, msgs) ->
                val sortedMsgs = msgs.sortedBy { it.timestamp }
                val lastMsg = sortedMsgs.lastOrNull()
                val unreadCount = msgs.count { it.to == ownerAddress && !it.read }

                Conversation(
                    contactAddress = address,
                    contactName = contacts[address]?.name,
                    messages = sortedMsgs,
                    lastMessage = lastMsg,
                    unreadCount = unreadCount
                )
            }
            .sortedByDescending { it.lastMessage?.timestamp }

        return conversations
    }

    fun getMessages(address1: String, address2: String): List<Message> {
        val msgs1 = messages[address1] ?: return emptyList()
        return msgs1.filter {
            (it.from == address1 && it.to == address2) ||
            (it.from == address2 && it.to == address1)
        }.sortedBy { it.timestamp }
    }

    fun markAsRead(messageId: String, ownerAddress: String) {
        messages[ownerAddress]?.forEach { msg ->
            if (msg.id == messageId) {
                val index = messages[ownerAddress]?.indexOf(msg) ?: return
                if (index >= 0) {
                    messages[ownerAddress]?.set(index, msg.copy(read = true))
                }
            }
        }
    }

    fun markConversationAsRead(contactAddress: String, ownerAddress: String) {
        messages[ownerAddress]?.forEach { msg ->
            if (msg.from == contactAddress && msg.to == ownerAddress && !msg.read) {
                val index = messages[ownerAddress]?.indexOf(msg) ?: return
                if (index >= 0) {
                    messages[ownerAddress]?.set(index, msg.copy(read = true))
                }
            }
        }
    }
}
