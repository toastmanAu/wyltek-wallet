package com.wyltek.wallet.core.account

import com.wyltek.wallet.core.model.*
import java.security.SecureRandom
import java.util.UUID

class AccountManager {

    private val accounts = mutableMapOf<String, WalletAccount>()
    private val secureRandom = SecureRandom()

    fun createClassicAccount(
        name: String,
        network: NetworkType = NetworkType.TESTNET,
        mnemonic: String? = null
    ): WalletAccount {
        val id = UUID.randomUUID().toString()
        val account = WalletAccount(
            id = id,
            name = name,
            type = AccountType.CLASSIC,
            network = network,
            addresses = emptyList(),
            createdAt = System.currentTimeMillis()
        )
        accounts[id] = account
        return account
    }

    fun createPqAccount(
        name: String,
        network: NetworkType = NetworkType.TESTNET,
        algorithm: LockAlgorithm = LockAlgorithm.ML_DSA_65
    ): WalletAccount {
        val id = UUID.randomUUID().toString()
        val account = WalletAccount(
            id = id,
            name = name,
            type = AccountType.POST_QUANTUM,
            network = network,
            addresses = emptyList(),
            createdAt = System.currentTimeMillis()
        )
        accounts[id] = account
        return account
    }

    fun createHybridAccount(
        name: String,
        network: NetworkType = NetworkType.TESTNET
    ): WalletAccount {
        val id = UUID.randomUUID().toString()
        val account = WalletAccount(
            id = id,
            name = name,
            type = AccountType.HYBRID,
            network = network,
            addresses = emptyList(),
            createdAt = System.currentTimeMillis()
        )
        accounts[id] = account
        return account
    }

    fun getAccount(id: String): WalletAccount? = accounts[id]

    fun getAllAccounts(): List<WalletAccount> = accounts.values.toList()

    fun getAccountsByType(type: AccountType): List<WalletAccount> =
        accounts.values.filter { it.type == type }

    fun deleteAccount(id: String): Boolean = accounts.remove(id) != null

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
        accounts[id] = account
        return account
    }
}
