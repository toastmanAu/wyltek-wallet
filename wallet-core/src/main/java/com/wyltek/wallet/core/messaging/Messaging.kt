package com.wyltek.wallet.core.messaging

import java.util.UUID

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

/**
 * Messaging façade.  On-chain operations are injected via optional suspend callbacks
 * so that wallet-core never depends on :app (which holds WalletRepository).
 * When a callback is not supplied the method silently no-ops / returns a stub value,
 * keeping the existing unit-test and UI paths working unchanged.
 *
 * @param onSendMessage       Called by [sendMessage] after storing the local copy.
 *                            Receives (from, to, plaintextHex); returns the broadcast
 *                            tx hash or null on failure. Null = not wired.
 * @param onCreateProfileCell Called by [createProfileCell]; receives (ownerAddress, name);
 *                            returns broadcast tx hash. Null = stub UUID returned.
 * @param onDiscoverProfile   Called by [discoverProfile]; receives the recipient address;
 *                            returns a [ContactProfile] populated with the on-chain KEM
 *                            public key. Null = stub profile returned.
 * @param onDecryptMessage    Called by [decryptMessage]; receives (ciphertextHex, ownerAddress);
 *                            returns plaintext bytes. Null = empty bytes returned.
 */
class MessagingService(
    private val onSendMessage: (suspend (from: String, to: String, plaintextHex: String) -> String?)? = null,
    private val onCreateProfileCell: (suspend (ownerAddress: String, name: String) -> String?)? = null,
    private val onDiscoverProfile: (suspend (address: String) -> ContactProfile?)? = null,
    private val onDecryptMessage: (suspend (ciphertextHex: String, ownerAddress: String) -> ByteArray?)? = null,
) {

    private val messages = mutableMapOf<String, MutableList<Message>>()
    private val contacts = mutableMapOf<String, ContactProfile>()

    suspend fun createProfileCell(
        ownerAddress: String,
        name: String
    ): String? {
        return onCreateProfileCell?.invoke(ownerAddress, name)
            ?: UUID.randomUUID().toString()
    }

    suspend fun discoverProfile(address: String): ContactProfile? {
        val onChain = onDiscoverProfile?.invoke(address)
        if (onChain != null) {
            contacts[address] = onChain
            return onChain
        }
        return contacts[address] ?: ContactProfile(
            address = address,
            name = null,
            publicKey = null,
            discovered = false
        )
    }

    /**
     * Decrypt a CEMP-PQ ciphertext for [ownerAddress].
     * Delegates to [onDecryptMessage] when wired; returns empty bytes otherwise
     * (preserves the old stub behaviour for tests that don't inject the callback).
     */
    suspend fun decryptMessage(
        ciphertext: ByteArray,
        ownerAddress: String
    ): ByteArray {
        val ciphertextHex = "0x" + ciphertext.joinToString("") { "%02x".format(it) }
        return onDecryptMessage?.invoke(ciphertextHex, ownerAddress) ?: ByteArray(0)
    }

    /**
     * Send a message from [from] to [to] with [content] as plaintext bytes.
     * Stores locally, then delegates on-chain send to [onSendMessage] if wired.
     * The signature is UNCHANGED from the original stub so WalletViewModel /
     * MessagesScreen compile without modification.
     */
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

        // Route on-chain when wired.  Content bytes → hex; the on-chain path
        // handles discover + encrypt + broadcast.
        if (onSendMessage != null) {
            val plaintextHex = "0x" + content.joinToString("") { "%02x".format(it) }
            onSendMessage.invoke(from, to, plaintextHex)
        }

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
