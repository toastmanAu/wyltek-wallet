package com.wyltek.wallet.core.watchonly

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WatchOnlyAccount(
    val id: String,
    val name: String,
    val xpub: String,
    val derivationPath: String,
    val addressIndex: Int = 0,
    val createdAt: Long,
    val addresses: List<String> = emptyList()
)

class WatchOnlyManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("watchonly_manager", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val accounts = mutableListOf<WatchOnlyAccount>()

    init {
        loadAccounts()
    }

    fun importXpub(
        name: String,
        xpub: String,
        derivationPath: String = "m/44'/302'/0'"
    ): WatchOnlyAccount {
        val account = WatchOnlyAccount(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            xpub = xpub,
            derivationPath = derivationPath,
            createdAt = System.currentTimeMillis(),
            addresses = generateAddressesFromXpub(xpub, derivationPath, 0, 5)
        )

        accounts.add(account)
        saveAccounts()

        return account
    }

    fun getAccount(id: String): WatchOnlyAccount? {
        return accounts.find { it.id == id }
    }

    fun getAllAccounts(): List<WatchOnlyAccount> {
        return accounts.toList()
    }

    fun deleteAccount(id: String) {
        accounts.removeAll { it.id == id }
        saveAccounts()
    }

    fun refreshAddresses(accountId: String): WatchOnlyAccount? {
        val account = getAccount(accountId) ?: return null
        val updatedAddresses = generateAddressesFromXpub(account.xpub, account.derivationPath, 0, 10)
        val updatedAccount = account.copy(addresses = updatedAddresses)

        val index = accounts.indexOfFirst { it.id == accountId }
        if (index >= 0) {
            accounts[index] = updatedAccount
            saveAccounts()
        }

        return updatedAccount
    }

    fun parseXpub(xpub: String): XpubInfo? {
        return try {
            val cleanXpub = xpub.trim()
            if (cleanXpub.length != 111 && cleanXpub.length != 112) {
                return null
            }

            if (!cleanXpub.startsWith("xpub") && !cleanXpub.startsWith("ypub") && !cleanXpub.startsWith("zpub")) {
                return null
            }

            XpubInfo(
                version = cleanXpub.take(4),
                fingerprint = cleanXpub.substring(4, 12),
                chainCode = cleanXpub.substring(12, 76),
                publicKey = cleanXpub.substring(76, 111),
                depth = cleanXpub.substring(111, 112).toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }

    fun validateXpub(xpub: String): Boolean {
        return parseXpub(xpub) != null
    }

    private fun generateAddressesFromXpub(
        xpub: String,
        derivationPath: String,
        startIndex: Int,
        count: Int
    ): List<String> {
        val addresses = mutableListOf<String>()
        val xpubInfo = parseXpub(xpub) ?: return addresses

        for (i in startIndex until startIndex + count) {
            val address = deriveCKBAddress(xpubInfo, i)
            addresses.add(address)
        }

        return addresses
    }

    private fun deriveCKBAddress(xpubInfo: XpubInfo, index: Int): String {
        val pubkeyHash = hashPublicKey(xpubInfo.publicKey)
        val args = pubkeyHash.take(40)
        return "ckbtest:$args"
    }

    private fun hashPublicKey(publicKey: String): String {
        val bytes = publicKey.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun saveAccounts() {
        try {
            val jsonString = json.encodeToString(accounts)
            prefs.edit().putString("accounts", jsonString).apply()
        } catch (e: Exception) {
            // Handle serialization error
        }
    }

    private fun loadAccounts() {
        val jsonString = prefs.getString("accounts", null)
        if (jsonString != null) {
            try {
                val loaded = json.decodeFromString<List<WatchOnlyAccount>>(jsonString)
                accounts.addAll(loaded)
            } catch (e: Exception) {
                accounts.clear()
            }
        }
    }
}

@Serializable
data class XpubInfo(
    val version: String,
    val fingerprint: String,
    val chainCode: String,
    val publicKey: String,
    val depth: Int
)
