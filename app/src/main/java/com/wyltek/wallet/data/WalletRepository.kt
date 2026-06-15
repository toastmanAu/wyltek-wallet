package com.wyltek.wallet.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.wyltek.wallet.core.account.AccountManager
import com.wyltek.wallet.core.assets.AssetInfo
import com.wyltek.wallet.core.assets.AssetScanner
import com.wyltek.wallet.core.assets.ListingService
import com.wyltek.wallet.core.chain.CellsCapacity
import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.DaoProvider
import com.wyltek.wallet.core.chain.HeaderInfo
import com.wyltek.wallet.core.chain.NetworkConfig
import com.wyltek.wallet.core.chain.RpcProfile
import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.keystore.SeedVault
import com.wyltek.wallet.core.messaging.ContactBook
import com.wyltek.wallet.core.messaging.MessagingService
import com.wyltek.wallet.core.model.*
import com.wyltek.wallet.core.native.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.wyltek.wallet.core.passkey.JoyIDIntegration
import com.wyltek.wallet.core.passkey.PasskeyManager
import com.wyltek.wallet.core.security.StrongBoxManager
import com.wyltek.wallet.core.skin.SkinManager
import com.wyltek.wallet.core.watchonly.WatchOnlyManager
import java.util.UUID

class WalletRepository(context: Context) {

    private val appContext = context.applicationContext
    private val accountManager = AccountManager(context)
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
    private val strongBoxManager = StrongBoxManager(context)
    private val customTokenStore = CustomTokenStore(context)
    private val networkStore = NetworkStore(context)
    private val daoProvider = DaoProvider(chainManager)

    init {
        // Register providers for all supported networks
        val testnetProfile = RpcProfile(
            name = NetworkConfig.forNetwork(NetworkType.TESTNET).rpcName,
            url = NetworkConfig.forNetwork(NetworkType.TESTNET).rpcUrl,
            isPrivate = false
        )
        val mainnetProfile = RpcProfile(
            name = NetworkConfig.forNetwork(NetworkType.MAINNET).rpcName,
            url = NetworkConfig.forNetwork(NetworkType.MAINNET).rpcUrl,
            isPrivate = false
        )
        chainManager.addProvider(testnetProfile, testnetProfile.url)
        chainManager.addProvider(mainnetProfile, mainnetProfile.url)

        // Set active provider based on saved preference
        val savedNetwork = networkStore.getCurrentNetwork()
        val targetName = NetworkConfig.forNetwork(savedNetwork).rpcName
        chainManager.setActiveProvider(targetName)
    }

    fun getListingService(): ListingService = listingService
    fun getMessagingService(): MessagingService = messagingService
    fun getContactBook(): ContactBook = contactBook
    fun getSkinManager(): SkinManager = skinManager
    fun getPasskeyManager(): PasskeyManager = passkeyManager
    fun getJoyIDIntegration(): JoyIDIntegration = joyIdIntegration
    fun getWatchOnlyManager(): WatchOnlyManager = watchOnlyManager
    fun getStrongBoxManager(): StrongBoxManager = strongBoxManager
    fun getCustomTokenStore(): CustomTokenStore = customTokenStore
    fun getChainManager(): ChainManager = chainManager

    fun getCurrentNetwork(): NetworkType = networkStore.getCurrentNetwork()

    fun setCurrentNetwork(network: NetworkType) {
        networkStore.setCurrentNetwork(network)
        val targetName = NetworkConfig.forNetwork(network).rpcName
        chainManager.setActiveProvider(targetName)
    }

    fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun setActiveRpc(name: String) {
        chainManager.setActiveProvider(name)
    }

    fun getActiveRpcName(): String? = chainManager.getActiveProvider()?.name

    fun getAvailableRpcs(): List<String> = chainManager.getAllProviders().map { it.name }

    suspend fun scanDaoDeposits(lockScript: LockScript, network: NetworkType): WalletResult<List<DaoDeposit>> {
        return try {
            val deposits = daoProvider.scanDaoDeposits(lockScript, network)
            WalletResult.Success(deposits)
        } catch (e: Exception) {
            WalletResult.Error("DAO scan failed: ${e.message}")
        }
    }

    suspend fun getDaoOverview(deposits: List<DaoDeposit>): DaoOverview {
        return daoProvider.getDaoOverview(deposits)
    }

    suspend fun depositDao(
        fromAccount: WalletAccount,
        amountCkb: ULong,
        feeRate: ULong = 1000u
    ): WalletResult<String> {
        return try {
            Log.d("WalletRepo", "depositDao: amountCkb=$amountCkb, account=${fromAccount.name}")
            val seed = seedVault.loadSeed(fromAccount.id)
                ?: return WalletResult.Error("Seed not found")
            val keyPair = generateSecp256k1Keypair(seed, "m/44'/302'/0'/0/0")

            val fromAddress = fromAccount.addresses.firstOrNull()?.bech32m
                ?: return WalletResult.Error("No from address")
            val fromInfo = decodeAddress(fromAddress)

            val lockScript = LockScript(
                codeHash = fromInfo.lockCodeHash,
                hashType = fromInfo.lockHashType,
                args = fromInfo.lockArgs
            )

            Log.d("WalletRepo", "depositDao: querying cells for lockScript codeHash=${lockScript.codeHash} hashType=${lockScript.hashType} args=${lockScript.args}")
            val cells = chainManager.getCellsByLock(lockScript)
            if (cells.isEmpty()) {
                Log.e("WalletRepo", "depositDao: no cells found for lockScript=$lockScript")
                return WalletResult.Error("No available cells")
            }

            Log.d("WalletRepo", "depositDao: found ${cells.size} cells, total capacity=${cells.sumOf { it.capacity }}, selecting inputs...")

            val networkConfig = NetworkConfig.forNetwork(fromAccount.network)
            val amountShannons = amountCkb * 100_000_000uL
            val minCellCapacity = 61_0000_0000uL
            val feeEstimate = 1000uL

            val sortedCells = cells.sortedBy { it.capacity }
            val selected = mutableListOf<Utxo>()
            var selectedCapacity = 0uL
            for (cell in sortedCells) {
                selected.add(cell)
                selectedCapacity += cell.capacity
                if (selectedCapacity >= amountShannons + feeEstimate + minCellCapacity) break
            }

            if (selectedCapacity < amountShannons + feeEstimate) {
                Log.e("WalletRepo", "depositDao: insufficient balance, need=${amountShannons + feeEstimate}, have=$selectedCapacity")
                return WalletResult.Error("Insufficient balance for DAO deposit")
            }

            val needsChange = selectedCapacity >= amountShannons + feeEstimate + minCellCapacity

            val outputs = mutableListOf(
                com.wyltek.wallet.core.native.TxOutput(
                    capacity = amountShannons,
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    typeCodeHash = networkConfig.daoTypeCodeHash,
                    typeHashType = networkConfig.daoTypeHashType,
                    typeArgs = "0x",
                    data = "0x0000000000000000"
                )
            )

            if (needsChange) {
                val changeCapacity = selectedCapacity - amountShannons - feeEstimate
                outputs.add(
                    com.wyltek.wallet.core.native.TxOutput(
                        capacity = changeCapacity,
                        lockCodeHash = fromInfo.lockCodeHash,
                        lockHashType = fromInfo.lockHashType,
                        lockArgs = fromInfo.lockArgs,
                        typeCodeHash = "",
                        typeHashType = "",
                        typeArgs = "",
                        data = ""
                    )
                )
            }

            val cellDeps = listOf(
                com.wyltek.wallet.core.native.TxCellDep(
                    txHash = networkConfig.secp256k1DepGroupTxHash,
                    index = networkConfig.secp256k1DepGroupIndex,
                    depType = 1u
                ),
                com.wyltek.wallet.core.native.TxCellDep(
                    txHash = networkConfig.daoCellDepTxHash,
                    index = networkConfig.daoCellDepIndex,
                    depType = 0u
                )
            )

            val request = com.wyltek.wallet.core.native.TransactionRequest(
                inputs = selected.map {
                    com.wyltek.wallet.core.native.TxInput(
                        txHash = it.outPoint.txHash,
                        index = it.outPoint.index,
                        since = 0uL,
                        capacity = it.capacity
                    )
                },
                outputs = outputs,
                cellDeps = cellDeps,
                feeRate = feeRate
            )

            val built = buildTransaction(request)

            // Canonical secp256k1_blake160_sighash_all: full WitnessArgs(lock=sig)
            // for witnesses[0] (single secp group; same proven primitive as sendCkb).
            val signature = signCkbSecp256k1Witness(
                built.txHashHex,
                selected.size.toUInt(),
                keyPair.privateKeyHex
            )

            val txJson = buildJsonObject {
                put("version", "0x0")
                put("cell_deps", buildJsonArray {
                    for (dep in cellDeps) {
                        add(buildJsonObject {
                            put("out_point", buildJsonObject {
                                put("tx_hash", "0x${dep.txHash}")
                                put("index", "0x${dep.index.toString(16)}")
                            })
                            put("dep_type", if (dep.depType.toInt() == 1) "dep_group" else "code")
                        })
                    }
                })
                put("header_deps", buildJsonArray {})
                put("inputs", buildJsonArray {
                    for (input in selected) {
                        add(buildJsonObject {
                            put("previous_output", buildJsonObject {
                                put("tx_hash", input.outPoint.txHash)
                                put("index", "0x${input.outPoint.index.toString(16)}")
                            })
                            put("since", "0x0")
                        })
                    }
                })
                put("outputs", buildJsonArray {
                    for (output in outputs) {
                        add(buildJsonObject {
                            put("capacity", "0x${output.capacity.toString(16)}")
                            put("lock", buildJsonObject {
                                put("code_hash", output.lockCodeHash)
                                put("hash_type", output.lockHashType)
                                put("args", output.lockArgs)
                            })
                            if (output.typeCodeHash.isNotBlank()) {
                                put("type", buildJsonObject {
                                    put("code_hash", output.typeCodeHash)
                                    put("hash_type", output.typeHashType)
                                    put("args", output.typeArgs)
                                })
                            }
                        })
                    }
                })
                put("outputs_data", buildJsonArray {
                    for (output in outputs) {
                        add(output.data.ifEmpty { "0x" })
                    }
                })
                put("witnesses", buildJsonArray {
                    add("0x$signature")
                    for (i in 1 until selected.size) {
                        add("0x")
                    }
                })
            }

            val txHash = chainManager.sendTransactionJson(txJson)
            Log.d("WalletRepo", "depositDao: txHash=$txHash")
            if (txHash == null) {
                return WalletResult.Error("DAO deposit transaction broadcast failed")
            }

            WalletResult.Success(txHash)
        } catch (e: Exception) {
            Log.e("WalletRepo", "depositDao failed: ${e.message}", e)
            WalletResult.Error("DAO deposit failed: ${e.message}")
        }
    }

    suspend fun withdrawDaoPhase1(
        fromAccount: WalletAccount,
        deposit: DaoDeposit,
        feeRate: ULong = 1000u
    ): WalletResult<String> = withContext(Dispatchers.IO) {
        try {
            val seed = seedVault.loadSeed(fromAccount.id)
                ?: return@withContext WalletResult.Error("Seed not found")
            val keyPair = generateSecp256k1Keypair(seed, "m/44'/302'/0'/0/0")

            val fromAddress = fromAccount.addresses.firstOrNull()?.bech32m
                ?: return@withContext WalletResult.Error("No from address")
            val fromInfo = decodeAddress(fromAddress)

            val lockScript = LockScript(
                codeHash = fromInfo.lockCodeHash,
                hashType = fromInfo.lockHashType,
                args = fromInfo.lockArgs
            )

            val networkConfig = NetworkConfig.forNetwork(fromAccount.network)

            val daoTypeScript = LockScript(
                codeHash = networkConfig.daoTypeCodeHash,
                hashType = networkConfig.daoTypeHashType,
                args = "0x"
            )

            val daoCells = chainManager.getCellsByLockAndType(lockScript, daoTypeScript)
            val targetCell = daoCells.find {
                it.outPoint.txHash == deposit.outPoint.txHash && it.outPoint.index == deposit.outPoint.index
            } ?: return@withContext WalletResult.Error("DAO deposit cell not found on chain")

            val cells = chainManager.getCellsByLock(lockScript)
            val feeEstimate = 1000uL
            val minCellCapacity = 61_0000_0000uL

            val nonDaoCells = cells
                .filter { it.outPoint.txHash != targetCell.outPoint.txHash || it.outPoint.index != targetCell.outPoint.index }
                .sortedBy { it.capacity }

            val changeCells = mutableListOf<Utxo>()
            var changeCapacity = 0uL
            for (cell in nonDaoCells) {
                changeCells.add(cell)
                changeCapacity += cell.capacity
                if (changeCapacity >= feeEstimate + minCellCapacity) break
            }

            val allInputs = mutableListOf(targetCell)
            allInputs.addAll(changeCells)
            val totalInput = allInputs.sumOf { it.capacity }

            val depositBlockNumberHex = "0x${deposit.depositBlockNumber.toString(16)}"

            val outputs = mutableListOf(
                com.wyltek.wallet.core.native.TxOutput(
                    capacity = targetCell.capacity,
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    typeCodeHash = networkConfig.daoTypeCodeHash,
                    typeHashType = networkConfig.daoTypeHashType,
                    typeArgs = "0x",
                    data = depositBlockNumberHex
                )
            )

            val changeAmount = totalInput - targetCell.capacity - feeEstimate
            if (changeAmount >= minCellCapacity) {
                outputs.add(
                    com.wyltek.wallet.core.native.TxOutput(
                        capacity = changeAmount,
                        lockCodeHash = fromInfo.lockCodeHash,
                        lockHashType = fromInfo.lockHashType,
                        lockArgs = fromInfo.lockArgs,
                        typeCodeHash = "",
                        typeHashType = "",
                        typeArgs = "",
                        data = ""
                    )
                )
            }

            val cellDeps = listOf(
                com.wyltek.wallet.core.native.TxCellDep(
                    txHash = networkConfig.secp256k1DepGroupTxHash,
                    index = networkConfig.secp256k1DepGroupIndex,
                    depType = 1u
                ),
                com.wyltek.wallet.core.native.TxCellDep(
                    txHash = networkConfig.daoCellDepTxHash,
                    index = networkConfig.daoCellDepIndex,
                    depType = 0u
                )
            )

            val headerDeps = listOfNotNull(deposit.depositBlockHash.ifEmpty { null })

            val request = com.wyltek.wallet.core.native.TransactionRequest(
                inputs = allInputs.map {
                    com.wyltek.wallet.core.native.TxInput(
                        txHash = it.outPoint.txHash,
                        index = it.outPoint.index,
                        since = 0uL,
                        capacity = it.capacity
                    )
                },
                outputs = outputs,
                cellDeps = cellDeps,
                feeRate = feeRate
            )

            val built = buildTransaction(request)

            // Canonical secp256k1_blake160_sighash_all: full WitnessArgs(lock=sig)
            // for witnesses[0] (single secp group; same proven primitive as sendCkb).
            val signature = signCkbSecp256k1Witness(
                built.txHashHex,
                allInputs.size.toUInt(),
                keyPair.privateKeyHex
            )

            val txJson = buildJsonObject {
                put("version", "0x0")
                put("cell_deps", buildJsonArray {
                    for (dep in cellDeps) {
                        add(buildJsonObject {
                            put("out_point", buildJsonObject {
                                put("tx_hash", "0x${dep.txHash}")
                                put("index", "0x${dep.index.toString(16)}")
                            })
                            put("dep_type", if (dep.depType.toInt() == 1) "dep_group" else "code")
                        })
                    }
                })
                put("header_deps", buildJsonArray {
                    for (header in headerDeps) {
                        add(header)
                    }
                })
                put("inputs", buildJsonArray {
                    for (input in allInputs) {
                        add(buildJsonObject {
                            put("previous_output", buildJsonObject {
                                put("tx_hash", input.outPoint.txHash)
                                put("index", "0x${input.outPoint.index.toString(16)}")
                            })
                            put("since", "0x0")
                        })
                    }
                })
                put("outputs", buildJsonArray {
                    for (output in outputs) {
                        add(buildJsonObject {
                            put("capacity", "0x${output.capacity.toString(16)}")
                            put("lock", buildJsonObject {
                                put("code_hash", output.lockCodeHash)
                                put("hash_type", output.lockHashType)
                                put("args", output.lockArgs)
                            })
                            if (output.typeCodeHash.isNotBlank()) {
                                put("type", buildJsonObject {
                                    put("code_hash", output.typeCodeHash)
                                    put("hash_type", output.typeHashType)
                                    put("args", output.typeArgs)
                                })
                            }
                        })
                    }
                })
                put("outputs_data", buildJsonArray {
                    for (output in outputs) {
                        add(output.data.ifEmpty { "0x" })
                    }
                })
                put("witnesses", buildJsonArray {
                    add("0x$signature")
                    for (i in 1 until allInputs.size) {
                        add("0x")
                    }
                })
            }

            val txHash = chainManager.sendTransactionJson(txJson)
                ?: return@withContext WalletResult.Error("Phase 1 withdrawal broadcast failed")

            WalletResult.Success(txHash)
        } catch (e: Exception) {
            WalletResult.Error("Phase 1 withdrawal failed: ${e.message}")
        }
    }

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

    suspend fun fetchTransactionHistory(lockScript: LockScript): WalletResult<List<com.wyltek.wallet.core.chain.TransactionHistoryItem>> {
        return try {
            val history = chainManager.getTransactionsByLock(lockScript)
            WalletResult.Success(history)
        } catch (e: Exception) {
            WalletResult.Error("History fetch failed: ${e.message}")
        }
    }

    suspend fun scanTokens(lockScript: LockScript, network: NetworkType = NetworkType.TESTNET): WalletResult<List<com.wyltek.wallet.core.assets.TokenInfo>> {
        return try {
            val customTokens = customTokenStore.getAllTokens()
            val tokens = assetScanner.scanSudtCells(lockScript, customTokens, network)
            WalletResult.Success(tokens)
        } catch (e: Exception) {
            WalletResult.Error("Token scan failed: ${e.message}")
        }
    }

    fun createWallet(
        name: String,
        type: AccountType,
        network: NetworkType = NetworkType.TESTNET
    ): WalletResult<Pair<WalletAccount, String>> {
        return try {
            val mnemonicResult = generateMnemonic(24u)
            val seedHex = mnemonicResult.seedHex
            val mnemonicPhrase = mnemonicResult.mnemonic

            val addresses = buildAddressesForType(seedHex, type, network)

            val walletId = UUID.randomUUID().toString()
            val account = WalletAccount(
                id = walletId,
                name = name,
                type = type,
                network = network,
                addresses = addresses,
                createdAt = System.currentTimeMillis()
            )

            seedVault.storeSeed(walletId, seedHex)
            accountManager.saveAccount(account)

            WalletResult.Success(account to mnemonicPhrase)
        } catch (e: Exception) {
            WalletResult.Error("Failed to create wallet: ${e.message}")
        }
    }

    fun importWallet(
        name: String,
        mnemonic: String,
        type: AccountType = AccountType.CLASSIC,
        network: NetworkType = NetworkType.TESTNET
    ): WalletResult<WalletAccount> {
        return try {
            if (!validateMnemonic(mnemonic)) {
                return WalletResult.Error("Invalid mnemonic phrase")
            }

            val seedHex = mnemonicToSeed(mnemonic, "")
            val addresses = buildAddressesForType(seedHex, type, network)

            val walletId = UUID.randomUUID().toString()
            val account = WalletAccount(
                id = walletId,
                name = name,
                type = type,
                network = network,
                addresses = addresses,
                createdAt = System.currentTimeMillis()
            )

            seedVault.storeSeed(walletId, seedHex)
            accountManager.saveAccount(account)

            WalletResult.Success(account)
        } catch (e: Exception) {
            WalletResult.Error("Failed to create wallet: ${e.message}")
        }
    }

    fun generateMnemonicOnly(): String {
        val result = generateMnemonic(24u)
        return result.mnemonic
    }

    fun createWalletFromMnemonic(
        name: String,
        type: AccountType,
        mnemonic: String,
        network: NetworkType = NetworkType.TESTNET
    ): WalletResult<WalletAccount> {
        return try {
            val seedHex = mnemonicToSeed(mnemonic, "")
            val addresses = buildAddressesForType(seedHex, type, network)

            val walletId = UUID.randomUUID().toString()
            val account = WalletAccount(
                id = walletId,
                name = name,
                type = type,
                network = network,
                addresses = addresses,
                createdAt = System.currentTimeMillis()
            )

            seedVault.storeSeed(walletId, seedHex)
            accountManager.saveAccount(account)

            WalletResult.Success(account)
        } catch (e: Exception) {
            WalletResult.Error("Failed to create wallet: ${e.message}")
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

    suspend fun sendCkb(
        fromAccount: WalletAccount,
        toAddress: String,
        amount: ULong,
        feeRate: ULong = 1000u,
        fromCkbAddress: CkbAddress? = null
    ): WalletResult<String> {
        return try {
            val seed = seedVault.loadSeed(fromAccount.id)
                ?: return WalletResult.Error("Seed not found")

            val from = fromCkbAddress
                ?: fromAccount.addresses.firstOrNull()
                ?: return WalletResult.Error("No from address")
            val signCtx = resolveSigningContext(seed, from, fromAccount.network)
                ?: return WalletResult.Error(
                    "PQ lock not deployed on ${fromAccount.network} — set NetworkConfig.mldsa65 to the testnet deployment."
                )

            val fromInfo = decodeAddress(from.bech32m)
            val toInfo = decodeAddress(toAddress)

            val lockScript = LockScript(
                codeHash = fromInfo.lockCodeHash,
                hashType = fromInfo.lockHashType,
                args = fromInfo.lockArgs
            )

            val cells = chainManager.getCellsByLock(lockScript)
            if (cells.isEmpty()) {
                return WalletResult.Error("No available cells to spend")
            }

            val sortedCells = cells.sortedBy { it.capacity }
            val minCellCapacity = 81_0000_0000uL
            val feeEstimate = signCtx.minFeeEstimate

            val selected = mutableListOf<Utxo>()
            var selectedCapacity = 0uL
            for (cell in sortedCells) {
                selected.add(cell)
                selectedCapacity += cell.capacity
                if (selectedCapacity >= amount + feeEstimate + minCellCapacity) break
            }

            if (selectedCapacity < amount + feeEstimate) {
                return WalletResult.Error("Insufficient balance")
            }

            val needsChange = selectedCapacity >= amount + feeEstimate + minCellCapacity
            val outputs = mutableListOf(
                TxOutput(
                    capacity = amount,
                    lockCodeHash = toInfo.lockCodeHash,
                    lockHashType = toInfo.lockHashType,
                    lockArgs = toInfo.lockArgs,
                    typeCodeHash = "", typeHashType = "", typeArgs = "", data = ""
                )
            )

            if (needsChange) {
                outputs.add(
                    TxOutput(
                        capacity = selectedCapacity - amount - feeEstimate,
                        lockCodeHash = fromInfo.lockCodeHash,
                        lockHashType = fromInfo.lockHashType,
                        lockArgs = fromInfo.lockArgs,
                        typeCodeHash = "", typeHashType = "", typeArgs = "", data = ""
                    )
                )
            }

            val request = TransactionRequest(
                inputs = selected.map {
                    TxInput(
                        txHash = it.outPoint.txHash,
                        index = it.outPoint.index,
                        since = 0uL,
                        capacity = it.capacity
                    )
                },
                outputs = outputs,
                cellDeps = signCtx.cellDeps,
                feeRate = feeRate
            )

            val built = buildTransaction(request)
            // All inputs share the from-address lock (cells were fetched by it).
            // The ML-DSA-65 v2 lock signs a CighashAll digest over every input
            // cell, so the signer needs each input's CellOutput shape; secp
            // ignores this and only uses the count.
            val mldsaInputs = selected.map {
                MldsaInputCell(
                    capacity = it.capacity,
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    data = ""
                )
            }
            val witness0 = signCtx.signWitness0(built, mldsaInputs)

            val txJson = buildJsonObject {
                put("version", "0x0")
                put("cell_deps", buildJsonArray {
                    for (dep in signCtx.cellDepsForJson) {
                        add(buildJsonObject {
                            put("out_point", buildJsonObject {
                                put("tx_hash", dep.txHash)
                                put("index", "0x${dep.index.toString(16)}")
                            })
                            put("dep_type", dep.depType)
                        })
                    }
                })
                put("header_deps", buildJsonArray {})
                put("inputs", buildJsonArray {
                    for (input in selected) {
                        add(buildJsonObject {
                            put("previous_output", buildJsonObject {
                                put("tx_hash", input.outPoint.txHash)
                                put("index", "0x${input.outPoint.index.toString(16)}")
                            })
                            put("since", "0x0")
                        })
                    }
                })
                put("outputs", buildJsonArray {
                    for (output in outputs) {
                        add(buildJsonObject {
                            put("capacity", "0x${output.capacity.toString(16)}")
                            put("lock", buildJsonObject {
                                put("code_hash", output.lockCodeHash)
                                put("hash_type", output.lockHashType)
                                put("args", output.lockArgs)
                            })
                            if (output.typeCodeHash.isNotBlank()) {
                                put("type", buildJsonObject {
                                    put("code_hash", output.typeCodeHash)
                                    put("hash_type", output.typeHashType)
                                    put("args", output.typeArgs)
                                })
                            }
                        })
                    }
                })
                put("outputs_data", buildJsonArray {
                    for (output in outputs) {
                        add(output.data.ifEmpty { "0x" })
                    }
                })
                put("witnesses", buildJsonArray {
                    add(witness0)
                    for (i in 1 until selected.size) add("0x")
                })
            }

            val txHash = chainManager.sendTransactionJson(txJson)
                ?: return WalletResult.Error("Transaction broadcast failed")

            WalletResult.Success(txHash)
        } catch (e: Exception) {
            WalletResult.Error("Send failed: ${e.message}")
        }
    }

    suspend fun sendToken(
        fromAccount: WalletAccount,
        tokenTypeScript: LockScript,
        toAddress: String,
        amount: java.math.BigInteger,
        feeRate: ULong = 1000u,
        fromCkbAddress: CkbAddress? = null
    ): WalletResult<String> {
        return try {
            val seed = seedVault.loadSeed(fromAccount.id)
                ?: return WalletResult.Error("Seed not found")
            val keyPair = generateSecp256k1Keypair(seed, "m/44'/302'/0'/0/0")

            val from = fromCkbAddress ?: fromAccount.addresses.firstOrNull()
                ?: return WalletResult.Error("No from address")

            // sUDT sends from a PQ sub-account are not yet wired — the dual
            // cell-dep (secp + sudt) path and ML-DSA witness construction are
            // a separate refactor. Refuse explicitly rather than silently
            // signing with the wrong key.
            val mldsa = NetworkConfig.forNetwork(fromAccount.network).mldsa65
            if (mldsa != null && !mldsa.isPlaceholder() &&
                from.lockScript.codeHash.equals(mldsa.codeHash, ignoreCase = true)) {
                return WalletResult.Error(
                    "Token sends from PQ sub-accounts are not yet supported. " +
                        "Switch to the Classic sub-account in the send picker."
                )
            }

            val fromAddress = from.bech32m
            val fromInfo = decodeAddress(fromAddress)
            val toInfo = decodeAddress(toAddress)

            val fromLock = LockScript(
                codeHash = fromInfo.lockCodeHash,
                hashType = fromInfo.lockHashType,
                args = fromInfo.lockArgs
            )

            // Fetch sUDT cells
            val sudtCells = chainManager.getCellsByLock(fromLock).filter {
                it.type_?.codeHash.equals(tokenTypeScript.codeHash, ignoreCase = true) &&
                        it.type_?.hashType == tokenTypeScript.hashType &&
                        it.type_?.args == tokenTypeScript.args
            }

            if (sudtCells.isEmpty()) {
                return WalletResult.Error("No sUDT cells found")
            }

            // Sum sUDT balance
            var totalSudt = java.math.BigInteger.ZERO
            for (cell in sudtCells) {
                val data = cell.data?.removePrefix("0x") ?: ""
                val cellAmount = if (data.length >= 32) decodeU128Le(data) else java.math.BigInteger.ZERO
                totalSudt += cellAmount
            }

            if (totalSudt < amount) {
                return WalletResult.Error("Insufficient token balance")
            }

            // Select sUDT inputs
            val sudtInputs = mutableListOf<Utxo>()
            var selectedSudtAmount = java.math.BigInteger.ZERO
            for (cell in sudtCells) {
                sudtInputs.add(cell)
                val data = cell.data?.removePrefix("0x") ?: ""
                val cellAmount = if (data.length >= 32) decodeU128Le(data) else java.math.BigInteger.ZERO
                selectedSudtAmount += cellAmount
                if (selectedSudtAmount >= amount) break
            }

            // Minimum capacity for sUDT cell (occupied ~182 bytes)
            val minSudtCellCapacity = 200_0000_0000uL // 200 CKB in shannons

            // Fetch CKB cells for capacity
            val ckbCells = chainManager.getCellsByLock(fromLock).filter {
                it.type_ == null && it.data.isNullOrEmpty()
            }

            val sudtOutputCount = if (selectedSudtAmount > amount) 2uL else 1uL
            val neededCkb = sudtOutputCount * minSudtCellCapacity + 2000uL

            val selectedCkb = mutableListOf<Utxo>()
            var selectedCkbCapacity = 0uL
            for (cell in ckbCells) {
                selectedCkb.add(cell)
                selectedCkbCapacity += cell.capacity
                if (selectedCkbCapacity >= neededCkb) break
            }

            if (selectedCkbCapacity < neededCkb) {
                return WalletResult.Error("Insufficient CKB for token transfer fees")
            }

            // Build outputs
            val outputs = mutableListOf<TxOutput>()

            // Recipient sUDT
            outputs.add(TxOutput(
                capacity = minSudtCellCapacity,
                lockCodeHash = toInfo.lockCodeHash,
                lockHashType = toInfo.lockHashType,
                lockArgs = toInfo.lockArgs,
                typeCodeHash = tokenTypeScript.codeHash,
                typeHashType = tokenTypeScript.hashType,
                typeArgs = tokenTypeScript.args,
                data = amount.toU128LeHex()
            ))

            // sUDT change
            if (selectedSudtAmount > amount) {
                val changeAmount = selectedSudtAmount - amount
                outputs.add(TxOutput(
                    capacity = minSudtCellCapacity,
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    typeCodeHash = tokenTypeScript.codeHash,
                    typeHashType = tokenTypeScript.hashType,
                    typeArgs = tokenTypeScript.args,
                    data = changeAmount.toU128LeHex()
                ))
            }

            val allInputs = sudtInputs + selectedCkb
            val totalInputCapacity = selectedCkbCapacity + sudtInputs.sumOf { it.capacity }
            val ckbChangeLong = totalInputCapacity.toLong() - (sudtOutputCount.toLong() * minSudtCellCapacity.toLong())

            if (ckbChangeLong >= 81_0000_0000L) {
                outputs.add(TxOutput(
                    capacity = ckbChangeLong.toULong(),
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    typeCodeHash = "",
                    typeHashType = "",
                    typeArgs = "",
                    data = ""
                ))
            } else if (ckbChangeLong < 0) {
                return WalletResult.Error("Insufficient CKB capacity for token transfer")
            }

            val networkConfig = NetworkConfig.forNetwork(fromAccount.network)
            val cellDeps = listOf(
                TxCellDep(
                    txHash = networkConfig.secp256k1DepGroupTxHash,
                    index = networkConfig.secp256k1DepGroupIndex,
                    depType = 1u
                ),
                TxCellDep(
                    txHash = networkConfig.sudtCellDepTxHash,
                    index = networkConfig.sudtCellDepIndex,
                    depType = 0u
                )
            )

            val request = TransactionRequest(
                inputs = allInputs.map {
                    TxInput(
                        txHash = it.outPoint.txHash,
                        index = it.outPoint.index,
                        since = 0uL,
                        capacity = it.capacity
                    )
                },
                outputs = outputs,
                cellDeps = cellDeps,
                feeRate = feeRate
            )

            val built = buildTransaction(request)

            // Canonical secp256k1_blake160_sighash_all: full WitnessArgs(lock=sig)
            // for witnesses[0] (single secp group; same proven primitive as sendCkb).
            val signature = signCkbSecp256k1Witness(
                built.txHashHex,
                allInputs.size.toUInt(),
                keyPair.privateKeyHex
            )

            val txJson = buildJsonObject {
                put("version", "0x0")
                put("cell_deps", buildJsonArray {
                    for (dep in cellDeps) {
                        add(buildJsonObject {
                            put("out_point", buildJsonObject {
                                put("tx_hash", "0x${dep.txHash}")
                                put("index", "0x${dep.index.toString(16)}")
                            })
                            put("dep_type", if (dep.depType.toInt() == 1) "dep_group" else "code")
                        })
                    }
                })
                put("header_deps", buildJsonArray {})
                put("inputs", buildJsonArray {
                    for (input in allInputs) {
                        add(buildJsonObject {
                            put("previous_output", buildJsonObject {
                                put("tx_hash", input.outPoint.txHash)
                                put("index", "0x${input.outPoint.index.toString(16)}")
                            })
                            put("since", "0x0")
                        })
                    }
                })
                put("outputs", buildJsonArray {
                    for (output in outputs) {
                        add(buildJsonObject {
                            put("capacity", "0x${output.capacity.toString(16)}")
                            put("lock", buildJsonObject {
                                put("code_hash", output.lockCodeHash)
                                put("hash_type", output.lockHashType)
                                put("args", output.lockArgs)
                            })
                            if (output.typeCodeHash.isNotBlank()) {
                                put("type", buildJsonObject {
                                    put("code_hash", output.typeCodeHash)
                                    put("hash_type", output.typeHashType)
                                    put("args", output.typeArgs)
                                })
                            }
                        })
                    }
                })
                put("outputs_data", buildJsonArray {
                    for (output in outputs) {
                        add(output.data.ifEmpty { "0x" })
                    }
                })
                put("witnesses", buildJsonArray {
                    add("0x$signature")
                    for (i in 1 until allInputs.size) {
                        add("0x")
                    }
                })
            }

            val txHash = chainManager.sendTransactionJson(txJson)
                ?: return WalletResult.Error("Token transaction broadcast failed")

            WalletResult.Success(txHash)
        } catch (e: Exception) {
            WalletResult.Error("Token send failed: ${e.message}")
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

    private fun java.math.BigInteger.toU128LeHex(): String {
        val bytes = this.toByteArray()
        val clean = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        val padded = ByteArray(16)
        val start = 16 - clean.size
        if (start >= 0) {
            clean.copyInto(padded, start)
        }
        val le = padded.reversedArray()
        return "0x" + le.joinToString("") { "%02x".format(it) }
    }

    private fun decodeU128Le(hexData: String): java.math.BigInteger {
        val bytes = hexToBytes(hexData).take(16).reversed().toByteArray()
        return java.math.BigInteger(1, bytes)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x")
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toByte(16)
        }
        return bytes
    }

    // ---- Address-builder helpers ----------------------------------------

    private fun networkStr(network: NetworkType): String = when (network) {
        NetworkType.MAINNET -> "mainnet"
        NetworkType.TESTNET -> "testnet"
        NetworkType.DEVNET -> "devnet"
    }

    private fun buildClassicAddress(seedHex: String, network: NetworkType): CkbAddress {
        val keyPair = generateSecp256k1Keypair(seedHex, "m/44'/302'/0'/0/0")
        val bech32m = publicKeyToCkbAddress(keyPair.publicKeyHex, networkStr(network))
        val info = try { decodeAddress(bech32m) } catch (_: Exception) { null }
        return CkbAddress(
            bech32m = bech32m,
            lockScript = LockScript(
                codeHash = info?.lockCodeHash ?: "",
                hashType = info?.lockHashType ?: "type",
                args = info?.lockArgs ?: ""
            ),
            network = network,
            formatVersion = AddressFormatVersion.CKB2021
        )
    }

    /**
     * Build a CKB address locked by ckb-mldsa-lock, derived deterministically
     * from the same seed as the classic key. Returns null if the network has
     * no ML-DSA-65 lock configured or the deployment is still a placeholder.
     */
    private fun buildPqAddress(seedHex: String, network: NetworkType): CkbAddress? {
        val mldsa = NetworkConfig.forNetwork(network).mldsa65 ?: return null
        if (mldsa.isPlaceholder()) return null
        val pqKey = mldsa65FromSeed(seedHex)
        // mldsa65LockArgsV2 produces the 36-byte args layout the deployed
        // ckb-mldsa-lock contract verifies against.
        val args = "0x" + mldsa65LockArgsV2(pqKey.publicKeyHex)
        val bech32m = encodeAddress(mldsa.codeHash, mldsa.hashType, args, networkStr(network))
        return CkbAddress(
            bech32m = bech32m,
            lockScript = LockScript(
                codeHash = mldsa.codeHash,
                hashType = mldsa.hashType,
                args = args
            ),
            network = network,
            formatVersion = AddressFormatVersion.CKB2021
        )
    }

    // ---- Signing dispatch ------------------------------------------------

    /**
     * dep_type field as it appears in the JSON-RPC tx envelope.
     */
    private data class JsonCellDep(val txHash: String, val index: UInt, val depType: String)

    /**
     * Captured signing context — algorithm-specific dep selection, witness
     * placeholder sizing, and final witness construction. Returned by
     * [resolveSigningContext]. Null result = network has no PQ deployment and
     * the address is PQ (caller surfaces a clear error).
     */
    private class SigningContext(
        val cellDeps: List<TxCellDep>,
        val cellDepsForJson: List<JsonCellDep>,
        /** Lower-bound fee to reserve for this algorithm, in shannons. */
        val minFeeEstimate: ULong,
        val signWitness0: (built: BuiltTransaction, inputs: List<MldsaInputCell>) -> String
    )

    private fun resolveSigningContext(
        seedHex: String,
        from: CkbAddress,
        network: NetworkType
    ): SigningContext? {
        val cfg = NetworkConfig.forNetwork(network)
        val mldsa = cfg.mldsa65
        val codeHash = from.lockScript.codeHash.lowercase()
        val isPq = mldsa != null && !mldsa.isPlaceholder() &&
            codeHash == mldsa.codeHash.lowercase()

        return if (isPq) {
            // ML-DSA-65 path. signCkbMldsa65 produces the fully-formed
            // WitnessArgs(MldsaWitness(...)) bytes the deployed ckb-mldsa-lock
            // contract verifies. Signing digest is
            // blake2b("ckb-default-hash", "CKB-MLDSA-LOCK" || tx_hash).
            val pqKey = mldsa65FromSeed(seedHex)
            val mldsaCfg = mldsa!! // non-null when isPq is true
            // PQ witness is ~5337 bytes vs secp's ~85, so the per-tx fee at
            // the default 1 shannon/byte feeRate is at least ~5337 shannons.
            // Reserve a margin above that to cover other tx-size contributions.
            val pqFeeEstimate = 10_000uL
            SigningContext(
                cellDeps = listOf(
                    TxCellDep(
                        txHash = mldsaCfg.cellDepTxHash,
                        index = mldsaCfg.cellDepIndex,
                        depType = if (mldsaCfg.cellDepType == "dep_group") 1.toUByte() else 0.toUByte()
                    )
                ),
                cellDepsForJson = listOf(
                    JsonCellDep(mldsaCfg.cellDepTxHash, mldsaCfg.cellDepIndex, mldsaCfg.cellDepType)
                ),
                minFeeEstimate = pqFeeEstimate,
                signWitness0 = { built, inputs ->
                    val witnessHex = signCkbMldsa65(
                        built.txHashHex,
                        inputs,
                        pqKey.privateKeyHex,
                        pqKey.publicKeyHex
                    )
                    "0x$witnessHex"
                }
            )
        } else if (codeHash == "0x0000000000000000000000000000000000000000000000000000000000000000" && from.bech32m.isNotBlank()) {
            // Empty codeHash means decodeAddress failed silently during account
            // creation — refuse to sign.
            null
        } else {
            // Classic secp256k1 path.
            val keyPair = generateSecp256k1Keypair(seedHex, "m/44'/302'/0'/0/0")
            SigningContext(
                cellDeps = listOf(
                    TxCellDep(
                        txHash = cfg.secp256k1DepGroupTxHash,
                        index = cfg.secp256k1DepGroupIndex,
                        depType = 1.toUByte() // dep_group
                    )
                ),
                cellDepsForJson = listOf(
                    JsonCellDep(cfg.secp256k1DepGroupTxHash, cfg.secp256k1DepGroupIndex, "dep_group")
                ),
                minFeeEstimate = 1000uL,
                signWitness0 = { built, inputs ->
                    // Canonical secp256k1_blake160_sighash_all: the signer
                    // computes the ckb-default-hash sighash over the
                    // WitnessArgs-wrapped placeholder and returns the full
                    // WitnessArgs(lock = 65-byte sig) for witnesses[0].
                    val witnessHex = signCkbSecp256k1Witness(
                        built.txHashHex,
                        inputs.size.toUInt(),
                        keyPair.privateKeyHex
                    )
                    "0x$witnessHex"
                }
            )
        }
    }

    private fun buildAddressesForType(
        seedHex: String,
        type: AccountType,
        network: NetworkType
    ): List<CkbAddress> {
        val classic = lazy { buildClassicAddress(seedHex, network) }
        val pq = lazy { buildPqAddress(seedHex, network) }
        return when (type) {
            AccountType.CLASSIC -> listOf(classic.value)
            AccountType.POST_QUANTUM -> {
                val p = pq.value
                    ?: throw IllegalStateException(
                        "PQ accounts require an ML-DSA-65 lock deployment on $network. " +
                            "Update NetworkConfig.mldsa65 with the testnet deployment."
                    )
                listOf(p)
            }
            AccountType.HYBRID -> listOfNotNull(classic.value, pq.value)
        }
    }
}

private fun com.wyltek.wallet.core.chain.MldsaLockConfig.isPlaceholder(): Boolean {
    val stripped = codeHash.removePrefix("0x")
    return stripped.isEmpty() || stripped.all { it == '0' }
}

sealed class WalletResult<out T> {
    data class Success<T>(val data: T) : WalletResult<T>()
    data class Error(val message: String) : WalletResult<Nothing>()
}
