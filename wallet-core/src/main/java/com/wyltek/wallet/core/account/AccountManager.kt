package com.wyltek.wallet.core.account

import android.content.Context
import android.content.SharedPreferences
import com.wyltek.wallet.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class AccountManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wallet_accounts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val accounts = mutableMapOf<String, WalletAccount>()

    init {
        loadAll()
    }

    private fun loadAll() {
        accounts.clear()
        val all = prefs.all
        for ((key, value) in all) {
            if (key.startsWith("acct_")) {
                try {
                    val account = json.decodeFromString<WalletAccount>(value as String)
                    accounts[account.id] = account
                } catch (_: Exception) {}
            }
        }
    }

    private fun save(account: WalletAccount) {
        accounts[account.id] = account
        prefs.edit().putString("acct_${account.id}", json.encodeToString(account)).apply()
    }

    fun getAccount(id: String): WalletAccount? = accounts[id]

    fun saveAccount(account: WalletAccount) {
        save(account)
    }

    fun getAllAccounts(): List<WalletAccount> = accounts.values.toList()

    fun getAccountsByType(type: AccountType): List<WalletAccount> =
        accounts.values.filter { it.type == type }

    fun deleteAccount(id: String): Boolean {
        val removed = accounts.remove(id) != null
        if (removed) {
            prefs.edit().remove("acct_$id").apply()
        }
        return removed
    }

    fun importFromAddress(address: String): WalletAccount? {
        val id = UUID.randomUUID().toString()
        val account = WalletAccount(
            id = id,
            name = "Imported",
            type = AccountType.CLASSIC,
            network = NetworkType.TESTNET,
            addresses = emptyList(),
            createdAt = System.currentTimeMillis(),
            isHD = false
        )
        save(account)
        return account
    }
}
