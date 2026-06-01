package com.wyltek.wallet.core.passkey

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PasskeyCredential(
    val id: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val address: String,
    val createdAt: Long,
    val name: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasskeyCredential) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

@Serializable
data class JoyIDAccount(
    val address: String,
    val pubkeyHash: String,
    val name: String? = null,
    val linked: Boolean = false,
    val linkedAt: Long? = null
)

class PasskeyManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("passkey_manager", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val credentials = mutableListOf<PasskeyCredential>()
    private val joyIdAccounts = mutableListOf<JoyIDAccount>()

    init {
        loadCredentials()
        loadJoyIDAccounts()
    }

    fun createCredential(
        name: String,
        address: String
    ): PasskeyCredential {
        val keyPair = generatePasskeyKeyPair()
        val credential = PasskeyCredential(
            id = java.util.UUID.randomUUID().toString(),
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
            address = address,
            createdAt = System.currentTimeMillis(),
            name = name
        )

        credentials.add(credential)
        saveCredentials()

        return credential
    }

    fun getCredential(id: String): PasskeyCredential? {
        return credentials.find { it.id == id }
    }

    fun getAllCredentials(): List<PasskeyCredential> {
        return credentials.toList()
    }

    fun deleteCredential(id: String) {
        credentials.removeAll { it.id == id }
        saveCredentials()
    }

    fun linkJoyIDAccount(address: String, pubkeyHash: String, name: String?): JoyIDAccount {
        val account = JoyIDAccount(
            address = address,
            pubkeyHash = pubkeyHash,
            name = name,
            linked = true,
            linkedAt = System.currentTimeMillis()
        )

        val existingIndex = joyIdAccounts.indexOfFirst { it.address == address }
        if (existingIndex >= 0) {
            joyIdAccounts[existingIndex] = account
        } else {
            joyIdAccounts.add(account)
        }

        saveJoyIDAccounts()
        return account
    }

    fun getJoyIDAccount(address: String): JoyIDAccount? {
        return joyIdAccounts.find { it.address == address }
    }

    fun getAllJoyIDAccounts(): List<JoyIDAccount> {
        return joyIdAccounts.toList()
    }

    fun unlinkJoyIDAccount(address: String) {
        joyIdAccounts.removeAll { it.address == address }
        saveJoyIDAccounts()
    }

    fun signWithPasskey(credentialId: String, challenge: ByteArray): ByteArray? {
        val credential = getCredential(credentialId) ?: return null

        // In a real implementation, this would use Android Keystore to sign
        // For now, return null as a placeholder
        return null
    }

    private fun generatePasskeyKeyPair(): KeyPair {
        val keyPairGenerator = java.security.KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(java.security.spec.ECGenParameterSpec("secp256k1"))
        val keyPair = keyPairGenerator.generateKeyPair()

        return KeyPair(
            publicKey = keyPair.public.encoded,
            privateKey = keyPair.private.encoded
        )
    }

    private fun saveCredentials() {
        try {
            val jsonString = json.encodeToString(credentials)
            prefs.edit().putString("credentials", jsonString).apply()
        } catch (e: Exception) {
            // Handle serialization error
        }
    }

    private fun loadCredentials() {
        val jsonString = prefs.getString("credentials", null)
        if (jsonString != null) {
            try {
                val loaded = json.decodeFromString<List<PasskeyCredential>>(jsonString)
                credentials.addAll(loaded)
            } catch (e: Exception) {
                credentials.clear()
            }
        }
    }

    private fun saveJoyIDAccounts() {
        try {
            val jsonString = json.encodeToString(joyIdAccounts)
            prefs.edit().putString("joyid_accounts", jsonString).apply()
        } catch (e: Exception) {
            // Handle serialization error
        }
    }

    private fun loadJoyIDAccounts() {
        val jsonString = prefs.getString("joyid_accounts", null)
        if (jsonString != null) {
            try {
                val loaded = json.decodeFromString<List<JoyIDAccount>>(jsonString)
                joyIdAccounts.addAll(loaded)
            } catch (e: Exception) {
                joyIdAccounts.clear()
            }
        }
    }
}

data class KeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
)
