package com.wyltek.wallet.data

import android.content.Context
import com.wyltek.wallet.core.account.AccountManager
import com.wyltek.wallet.core.assets.AssetInfo
import com.wyltek.wallet.core.assets.AssetScanner
import com.wyltek.wallet.core.assets.ListingService
import com.wyltek.wallet.core.chain.CellsCapacity
import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.HeaderInfo
import com.wyltek.wallet.core.chain.RpcProfile
import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.keystore.SeedVault
import com.wyltek.wallet.core.messaging.ContactBook
import com.wyltek.wallet.core.messaging.MessagingService
import com.wyltek.wallet.core.model.*
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.core.passkey.JoyIDIntegration
import com.wyltek.wallet.core.passkey.PasskeyManager
import com.wyltek.wallet.core.skin.SkinManager
import com.wyltek.wallet.core.watchonly.WatchOnlyManager
import java.util.UUID

class WalletRepository(context: Context) {

    private val accountManager = AccountManager()
    private val seedVault = SeedVault(context)
    private val chainManager = ChainManager()
    private val assetScanner = AssetScanner(chainManager)
    private val listingService = ListingService(chainManager)
    private val messagingService = MessagingService()
    private val contactBook = ContactBook(context)
    private val skinManager = SkinManager(context)
    private val passkeyManager = PasskeyManager(context)
    private val joyIdIntegration = JoyIDIntegration(context)
    private val watchOnlyManager = WatchOnlyManager(context)

    init {
        chainManager.addProvider(
            RpcProfile(
                name = "CKB Testnet (public)",
                url = "https://testnet.ckbapp.dev",
                isPrivate = false
            )
        )
    }

    fun getListingService(): ListingService = listingService
    fun getMessagingService(): MessagingService = messagingService
    fun getContactBook(): ContactBook = contactBook
    fun getSkinManager(): SkinManager = skinManager
    fun getPasskeyManager(): PasskeyManager = passkeyManager
    fun getJoyIDIntegration(): JoyIDIntegration = joyIdIntegration
    fun getWatchOnlyManager(): WatchOnlyManager = watchOnlyManager

    fun setActiveRpc(name: String) {
        chainManager.setActiveProvider(name)
    }

    fun getActiveRpcName(): String? = chainManager.getActiveProvider()?.name

    fun getAvailableRpcs(): List<String> = chainManager.getAllProviders().map { it.name }

    suspend fun refreshBalance(lockScript: LockScript): WalletResult<CellsCapacity> {
        return try {
            val capacity = chainManager.getCellsCapacity(lockScript)
                ?: return WalletResult.Error("Failed to fetch balance")
            WalletResult.Success(capacity)
        } catch (e: Exception) {
            WalletResult.Error("Balance fetch failed: ${e.message}")
        }
    }

    suspend fun getTipHeader(): WalletResult<HeaderInfo> {
        return try {
            val header = chainManager.getTipHeader()
                ?: return WalletResult.Error("Failed to fetch tip header")
            WalletResult.Success(header)
        } catch (e: Exception) {
            WalletResult.Error("Header fetch failed: ${e.message}")
        }
    }

    suspend fun getCells(lockScript: LockScript): WalletResult<List<Utxo>> {
        return try {
            val cells = chainManager.getCellsByLock(lockScript)
            WalletResult.Success(cells)
        } catch (e: Exception) {
            WalletResult.Error("Cell fetch failed: ${e.message}")
        }
    }

    suspend fun sendTransaction(transaction: Transaction): WalletResult<String> {
        return try {
            val txHash = chainManager.sendTransaction(transaction)
                ?: return WalletResult.Error("Transaction broadcast failed")
            WalletResult.Success(txHash)
        } catch (e: Exception) {
            WalletResult.Error("Transaction send failed: ${e.message}")
        }
    }

    suspend fun getTransactionStatus(txHash: String): WalletResult<TxStatus> {
        return try {
            val status = chainManager.getActiveProvider()?.getTransactionStatus(txHash)
                ?: return WalletResult.Error("Failed to fetch tx status")
            WalletResult.Success(status)
        } catch (e: Exception) {
            WalletResult.Error("Status fetch failed: ${e.message}")
        }
    }

    suspend fun scanAssets(lockScript: LockScript): WalletResult<List<AssetInfo>> {
        return try {
            val assets = assetScanner.scanAll(lockScript)
            WalletResult.Success(assets)
        } catch (e: Exception) {
            WalletResult.Error("Asset scan failed: ${e.message}")
        }
    }

    fun createWallet(
        name: String,
        type: AccountType,
        network: NetworkType = NetworkType.TESTNET
    ): WalletResult<WalletAccount> {
        return try {
            val mnemonicResult = generateMnemonic(24u)
            val seedHex = mnemonicResult.seedHex

            val keyPair = generateSecp256k1Keypair(
                seedHex, "m/44'/302'/0'/0/0"
            )

            val networkStr = when (network) {
                NetworkType.MAINNET -> "mainnet"
                NetworkType.TESTNET -> "testnet"
                NetworkType.DEVNET -> "devnet"
            }

            val address = publicKeyToCkbAddress(
                keyPair.publicKeyHex, networkStr
            )

            val addressInfo = try {
                decodeAddress(address)
            } catch (e: Exception) {
                null
            }

            val ckbAddress = CkbAddress(
                bech32m = address,
                lockScript = LockScript(
                    codeHash = addressInfo?.lockCodeHash ?: "",
                    hashType = addressInfo?.lockHashType ?: "type",
                    args = addressInfo?.lockArgs ?: ""
                ),
                network = network,
                formatVersion = when (addressInfo?.formatVersion) {
                    "ckb2021" -> AddressFormatVersion.CKB2021
                    "deprecated-short" -> AddressFormatVersion.DEPRECATEDShort
                    else -> AddressFormatVersion.CKB2021
                }
            )

            val walletId = UUID.randomUUID().toString()
            val account = WalletAccount(
                id = walletId,
                name = name,
                type = type,
                network = network,
                addresses = listOf(ckbAddress),
                createdAt = System.currentTimeMillis()
            )

            seedVault.storeSeed(walletId, seedHex)

            WalletResult.Success(account)
        } catch (e: Exception) {
            WalletResult.Error("Failed to create wallet: ${e.message}")
        }
    }

    fun importWallet(
        name: String,
        mnemonic: String,
        network: NetworkType = NetworkType.TESTNET
    ): WalletResult<WalletAccount> {
        return try {
            if (!validateMnemonic(mnemonic)) {
                return WalletResult.Error("Invalid mnemonic phrase")
            }

            val seedHex = mnemonicToSeed(mnemonic, "")

            val keyPair = generateSecp256k1Keypair(
                seedHex, "m/44'/302'/0'/0/0"
            )

            val networkStr = when (network) {
                NetworkType.MAINNET -> "mainnet"
                NetworkType.TESTNET -> "testnet"
                NetworkType.DEVNET -> "devnet"
            }

            val address = publicKeyToCkbAddress(
                keyPair.publicKeyHex, networkStr
            )

            val addressInfo = try {
                decodeAddress(address)
            } catch (e: Exception) {
                null
            }

            val ckbAddress = CkbAddress(
                bech32m = address,
                lockScript = LockScript(
                    codeHash = addressInfo?.lockCodeHash ?: "",
                    hashType = addressInfo?.lockHashType ?: "type",
                    args = addressInfo?.lockArgs ?: ""
                ),
                network = network,
                formatVersion = AddressFormatVersion.CKB2021
            )

            val walletId = UUID.randomUUID().toString()
            val account = WalletAccount(
                id = walletId,
                name = name,
                type = AccountType.CLASSIC,
                network = network,
                addresses = listOf(ckbAddress),
                createdAt = System.currentTimeMillis()
            )

            seedVault.storeSeed(walletId, seedHex)

            WalletResult.Success(account)
        } catch (e: Exception) {
            WalletResult.Error("Import failed: ${e.message}")
        }
    }

    fun validateAddress(address: String): Boolean {
        return try {
            decodeAddress(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun buildTransaction(
        fromAddress: String,
        toAddress: String,
        amount: ULong,
        feeRate: ULong = 1000u
    ): WalletResult<String> {
        return try {
            val toInfo = decodeAddress(toAddress)

            val request = TransactionRequest(
                inputs = listOf(
                    TxInput(
                        txHash = "0".repeat(64),
                        index = 0u,
                        since = 0u
                    )
                ),
                outputs = listOf(
                    TxOutput(
                        capacity = amount,
                        lockCodeHash = toInfo.lockCodeHash,
                        lockHashType = toInfo.lockHashType,
                        lockArgs = toInfo.lockArgs,
                        typeCodeHash = "",
                        typeHashType = "",
                        typeArgs = ""
                    )
                ),
                feeRate = feeRate
            )

            val built = buildTransaction(request)
            WalletResult.Success(built.txHashHex)
        } catch (e: Exception) {
            WalletResult.Error("Transaction build failed: ${e.message}")
        }
    }

    fun getWalletSeed(walletId: String): String? {
        return seedVault.loadSeed(walletId)
    }

    fun deleteWallet(walletId: String): Boolean {
        seedVault.deleteSeed(walletId)
        return accountManager.deleteAccount(walletId)
    }

    fun getAllAccounts(): List<WalletAccount> = accountManager.getAllAccounts()

    fun getAccount(id: String): WalletAccount? = accountManager.getAccount(id)

    suspend fun listAssetForSale(
        asset: AssetInfo,
        price: ULong,
        royaltyPercent: UInt,
        expiryBlock: ULong?
    ): WalletResult<String> {
        return try {
            val listing = listingService.listForSale(asset, price, royaltyPercent, expiryBlock)
                ?: return WalletResult.Error("Failed to create listing")
            WalletResult.Success(listing.id)
        } catch (e: Exception) {
            WalletResult.Error("Listing failed: ${e.message}")
        }
    }

    suspend fun cancelListing(listingId: String): WalletResult<Boolean> {
        return try {
            val success = listingService.cancelListing(listingId)
            if (success) {
                WalletResult.Success(true)
            } else {
                WalletResult.Error("Failed to cancel listing")
            }
        } catch (e: Exception) {
            WalletResult.Error("Cancel failed: ${e.message}")
        }
    }

    suspend fun buyAsset(listingId: String): WalletResult<String> {
        return try {
            val listing = listingService.getListing(listingId)
                ?: return WalletResult.Error("Listing not found")

            val currentAccount = accountManager.getAllAccounts().firstOrNull()
                ?: return WalletResult.Error("No wallet available")

            val buyerLock = currentAccount.addresses.firstOrNull()?.lockScript
                ?: return WalletResult.Error("No lock script available")

            val cells = chainManager.getCellsByLock(buyerLock)
            if (cells.isEmpty()) {
                return WalletResult.Error("No cells available for payment")
            }

            val tx = listingService.buyAsset(listingId, buyerLock, cells)
                ?: return WalletResult.Error("Failed to build transaction")

            val txHash = chainManager.sendTransaction(tx)
                ?: return WalletResult.Error("Transaction broadcast failed")

            WalletResult.Success(txHash)
        } catch (e: Exception) {
            WalletResult.Error("Buy failed: ${e.message}")
        }
    }
}

sealed class WalletResult<out T> {
    data class Success<T>(val data: T) : WalletResult<T>()
    data class Error(val message: String) : WalletResult<Nothing>()
}
