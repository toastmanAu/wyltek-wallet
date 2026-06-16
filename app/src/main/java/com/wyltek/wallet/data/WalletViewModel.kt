package com.wyltek.wallet.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wyltek.wallet.core.assets.AssetInfo
import com.wyltek.wallet.core.assets.AssetType
import com.wyltek.wallet.core.assets.CustomTokenDefinition
import com.wyltek.wallet.core.assets.Listing
import com.wyltek.wallet.core.chain.CellsCapacity
import com.wyltek.wallet.core.chain.HeaderInfo
import com.wyltek.wallet.core.chain.RpcHealthStatus
import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.messaging.ContactProfile
import com.wyltek.wallet.core.messaging.Conversation
import com.wyltek.wallet.core.model.*
import com.wyltek.wallet.core.passkey.JoyIDAccount
import com.wyltek.wallet.core.passkey.PasskeyCredential
import com.wyltek.wallet.core.security.SecurityInfo
import com.wyltek.wallet.core.skin.ThemeConfig
import com.wyltek.wallet.core.skin.ThemePresets
import com.wyltek.wallet.core.watchonly.WatchOnlyAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

private const val TAG = "WalletVM"

data class WalletUiState(
    val accounts: List<WalletAccount> = emptyList(),
    val currentAccount: WalletAccount? = null,
    val balance: String = "0.00 CKB",
    val balanceCkb: ULong = 0u,
    val occupiedCapacity: ULong = 0u,
    val tipBlockNumber: Long = 0L,
    val isConnected: Boolean = false,
    val isSyncing: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val createdMnemonic: String? = null,
    val pendingMnemonic: String? = null,
    val pendingWalletName: String? = null,
    val pendingWalletType: AccountType? = null,
    val lastTxHash: String? = null,
    val lastTxStatus: TxStatus? = null,
    val addressCopied: Boolean = false,
    val activeRpc: String? = null,
    val allAssets: List<AssetInfo> = emptyList(),
    val sporeAssets: List<AssetInfo> = emptyList(),
    val cotaAssets: List<AssetInfo> = emptyList(),
    val ckbfsAssets: List<AssetInfo> = emptyList(),
    val selectedAsset: AssetInfo? = null,
    val isScanningAssets: Boolean = false,
    val activeListings: List<Listing> = emptyList(),
    val myListings: List<Listing> = emptyList(),
    val contacts: List<ContactProfile> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val currentTheme: ThemeConfig? = null,
    val customThemes: List<ThemeConfig> = emptyList(),
    val passkeyCredentials: List<PasskeyCredential> = emptyList(),
    val joyIdAccounts: List<JoyIDAccount> = emptyList(),
    val watchOnlyAccounts: List<WatchOnlyAccount> = emptyList(),
    val securityInfo: SecurityInfo? = null,
    val rpcHealthStatuses: List<RpcHealthStatus> = emptyList(),
    val transactionHistory: List<com.wyltek.wallet.core.chain.TransactionHistoryItem> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val tokenBalances: List<com.wyltek.wallet.core.assets.TokenInfo> = emptyList(),
    val selectedToken: com.wyltek.wallet.core.assets.TokenInfo? = null,
    val currentNetwork: NetworkType = NetworkType.TESTNET,
    val daoDeposits: List<DaoDeposit> = emptyList(),
    val daoOverview: DaoOverview = DaoOverview(),
    val daoSelectedTab: DaoTab = DaoTab.ACTIVE,
    val isLoadingDao: Boolean = false,
    val daoError: String? = null,
    val pendingDaoTxHash: String? = null,
    val pendingDaoAction: String? = null
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WalletRepository(application)
    private val listingService = repository.getListingService()
    private val messagingService = repository.getMessagingService()
    private val contactBook = repository.getContactBook()
    private val skinManager = repository.getSkinManager()
    private val passkeyManager = repository.getPasskeyManager()
    private val joyIdIntegration = repository.getJoyIDIntegration()
    private val watchOnlyManager = repository.getWatchOnlyManager()
    private val strongBoxManager = repository.getStrongBoxManager()

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        loadAccounts()
        _uiState.value = _uiState.value.copy(
            activeRpc = repository.getActiveRpcName(),
            currentTheme = skinManager.getCurrentTheme(),
            customThemes = skinManager.getCustomThemes(),
            passkeyCredentials = passkeyManager.getAllCredentials(),
            joyIdAccounts = passkeyManager.getAllJoyIDAccounts(),
            watchOnlyAccounts = watchOnlyManager.getAllAccounts(),
            securityInfo = strongBoxManager.getSecurityInfo(),
            currentNetwork = repository.getCurrentNetwork()
        )
        refreshListings()
        refreshMessaging()
    }

    private fun loadAccounts() {
        val accounts = repository.getAllAccounts()
        _uiState.value = _uiState.value.copy(
            accounts = accounts,
            currentAccount = accounts.firstOrNull()
        )
    }

    fun refreshBalance() {
        val account = _uiState.value.currentAccount
        if (account == null) {
            Log.d(TAG, "refreshBalance: no account, skipping")
            return
        }
        val lockScript = account.addresses.firstOrNull()?.lockScript
        if (lockScript == null) {
            Log.d(TAG, "refreshBalance: no lockScript, skipping")
            return
        }

        viewModelScope.launch {
            if (!repository.isNetworkAvailable()) {
                Log.d(TAG, "refreshBalance: no network")
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    isConnected = false
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSyncing = true)

            var lastError: String? = null
            for (attempt in 1..3) {
                Log.d(TAG, "refreshBalance: attempt $attempt/3")
                when (val result = repository.refreshBalance(lockScript)) {
                    is WalletResult.Success -> {
                        val capacity = result.data
                        val ckb = capacity.totalCapacity / 100_000_000u
                        val remainder = capacity.totalCapacity % 100_000_000u
                        val fraction = remainder.toString().padStart(8, '0').trimEnd('0')

                        Log.d(TAG, "refreshBalance: success, capacity=${capacity.totalCapacity}")
                        _uiState.value = _uiState.value.copy(
                            balance = if (fraction.isNotEmpty()) "$ckb.$fraction CKB" else "$ckb CKB",
                            balanceCkb = capacity.totalCapacity,
                            occupiedCapacity = capacity.occupiedCapacity,
                            isSyncing = false,
                            isConnected = true,
                            error = null
                        )
                        lastError = null
                        break
                    }
                    is WalletResult.Error -> {
                        Log.w(TAG, "refreshBalance: error on attempt $attempt: ${result.message}")
                        lastError = result.message
                        if (attempt < 3) delay(2000L * attempt)
                    }
                }
            }

            if (lastError != null) {
                Log.w(TAG, "refreshBalance: all attempts failed")
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    isConnected = false,
                    error = lastError
                )
            }
        }
    }

    fun syncTipHeader() {
        viewModelScope.launch {
            if (!repository.isNetworkAvailable()) {
                Log.d(TAG, "syncTipHeader: no network")
                _uiState.value = _uiState.value.copy(isConnected = false)
                return@launch
            }

            var success = false
            for (attempt in 1..3) {
                Log.d(TAG, "syncTipHeader: attempt $attempt/3")
                when (val result = repository.getTipHeader()) {
                    is WalletResult.Success -> {
                        val header = result.data
                        Log.d(TAG, "syncTipHeader: success, block=${header.number}")
                        _uiState.value = _uiState.value.copy(
                            tipBlockNumber = header.number.toLong(),
                            isConnected = true
                        )
                        success = true
                        break
                    }
                    is WalletResult.Error -> {
                        Log.w(TAG, "syncTipHeader: error on attempt $attempt: ${result.message}")
                        if (attempt < 3) delay(2000L * attempt)
                    }
                }
            }
            if (!success) {
                _uiState.value = _uiState.value.copy(isConnected = false)
            }
        }
    }

    fun refreshTransactionHistory() {
        val account = _uiState.value.currentAccount ?: return
        val lockScript = account.addresses.firstOrNull()?.lockScript ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingHistory = true)
            when (val result = repository.fetchTransactionHistory(lockScript)) {
                is WalletResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        transactionHistory = result.data,
                        isLoadingHistory = false,
                        error = null
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoadingHistory = false,
                        error = result.message
                    )
                }
            }
        }
    }

    suspend fun getTransactionStatus(txHash: String): String? {
        return when (val result = repository.getTransactionStatus(txHash)) {
            is WalletResult.Success -> result.data?.name?.lowercase()
            else -> null
        }
    }

    fun refreshAssets() {
        val account = _uiState.value.currentAccount ?: return
        val lockScript = account.addresses.firstOrNull()?.lockScript ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanningAssets = true)

            val assetResult = repository.scanAssets(lockScript)
            val tokenResult = repository.scanTokens(lockScript, account.network)

            when (assetResult) {
                is WalletResult.Success -> {
                    val assets = assetResult.data
                    _uiState.value = _uiState.value.copy(
                        allAssets = assets,
                        sporeAssets = assets.filter { it.type == AssetType.SPORE },
                        cotaAssets = assets.filter { it.type == AssetType.COTA },
                        ckbfsAssets = assets.filter { it.type == AssetType.CKBFS },
                        isScanningAssets = false
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isScanningAssets = false,
                        error = assetResult.message
                    )
                }
            }

            if (tokenResult is WalletResult.Success) {
                _uiState.value = _uiState.value.copy(
                    tokenBalances = tokenResult.data
                )
            }
        }
    }

    fun selectAsset(asset: AssetInfo) {
        _uiState.value = _uiState.value.copy(selectedAsset = asset)
    }

    fun clearSelectedAsset() {
        _uiState.value = _uiState.value.copy(selectedAsset = null)
    }

    fun selectToken(token: com.wyltek.wallet.core.assets.TokenInfo) {
        _uiState.value = _uiState.value.copy(selectedToken = token)
    }

    fun clearSelectedToken() {
        _uiState.value = _uiState.value.copy(selectedToken = null)
    }

    fun startAutoSync(intervalMs: Long = 30_000L) {
        stopAutoSync()
        syncJob = viewModelScope.launch {
            while (true) {
                syncTipHeader()
                delay(1000)
                refreshBalance()
                delay(1000)
                refreshAssets()
                delay(intervalMs)
            }
        }
    }

    fun stopAutoSync() {
        syncJob?.cancel()
        syncJob = null
    }

    fun createWallet(name: String, type: AccountType) {
        val mnemonic = repository.generateMnemonicOnly()
        _uiState.value = _uiState.value.copy(
            pendingMnemonic = mnemonic,
            pendingWalletName = name,
            pendingWalletType = type
        )
    }

    fun confirmWalletCreation() {
        val mnemonic = _uiState.value.pendingMnemonic ?: return
        val name = _uiState.value.pendingWalletName ?: return
        val type = _uiState.value.pendingWalletType ?: return
        val network = _uiState.value.currentNetwork

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = repository.createWalletFromMnemonic(name, type, mnemonic, network)) {
                is WalletResult.Success -> {
                    val account = result.data
                    _uiState.value = _uiState.value.copy(
                        accounts = _uiState.value.accounts + account,
                        currentAccount = account,
                        isLoading = false,
                        balance = "0.00 CKB",
                        createdMnemonic = mnemonic,
                        pendingMnemonic = null,
                        pendingWalletName = null,
                        pendingWalletType = null
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
                        pendingMnemonic = null,
                        pendingWalletName = null,
                        pendingWalletType = null
                    )
                }
            }
        }
    }

    fun importWallet(name: String, mnemonic: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val network = _uiState.value.currentNetwork

            when (val result = repository.importWallet(name, mnemonic, network = network)) {
                is WalletResult.Success -> {
                    val account = result.data
                    _uiState.value = _uiState.value.copy(
                        accounts = _uiState.value.accounts + account,
                        currentAccount = account,
                        isLoading = false
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun selectAccount(account: WalletAccount) {
        _uiState.value = _uiState.value.copy(currentAccount = account)
        refreshBalance()
        refreshAssets()
    }

    fun sendCkb(toAddress: String, amount: ULong, fromCkbAddress: com.wyltek.wallet.core.model.CkbAddress? = null) {
        viewModelScope.launch {
            val from = _uiState.value.currentAccount ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = repository.sendCkb(from, toAddress, amount, fromCkbAddress = fromCkbAddress)) {
                is WalletResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        lastTxHash = result.data,
                        error = null,
                        isLoading = false
                    )
                    refreshBalance()
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun sendToken(toAddress: String, tokenTypeScript: com.wyltek.wallet.core.model.LockScript, amount: java.math.BigInteger, fromCkbAddress: com.wyltek.wallet.core.model.CkbAddress? = null) {
        viewModelScope.launch {
            val from = _uiState.value.currentAccount ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = repository.sendToken(from, tokenTypeScript, toAddress, amount, fromCkbAddress = fromCkbAddress)) {
                is WalletResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        lastTxHash = result.data,
                        error = null,
                        isLoading = false
                    )
                    refreshBalance()
                    refreshAssets()
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun validateAddress(address: String): Boolean {
        return repository.validateAddress(address)
    }

    fun setActiveRpc(name: String) {
        repository.setActiveRpc(name)
        _uiState.value = _uiState.value.copy(activeRpc = name)
        refreshBalance()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearCreatedMnemonic() {
        _uiState.value = _uiState.value.copy(
            createdMnemonic = null,
            pendingMnemonic = null,
            pendingWalletName = null,
            pendingWalletType = null
        )
    }

    fun clearStaleCreationState() {
        _uiState.value = _uiState.value.copy(createdMnemonic = null)
    }

    fun clearLastTxHash() {
        _uiState.value = _uiState.value.copy(lastTxHash = null, lastTxStatus = null)
    }

    fun copyAddress() {
        _uiState.value = _uiState.value.copy(addressCopied = true)
    }

    fun getCurrentAddress(): String {
        return _uiState.value.currentAccount?.addresses?.firstOrNull()?.bech32m ?: ""
    }

    fun exportCurrentAccount(): String? {
        val walletId = _uiState.value.currentAccount?.id ?: return null
        return repository.getWalletSeed(walletId)
    }

    fun pairAccount() {
        _uiState.value = _uiState.value.copy(error = "Pair Account: coming soon (black box integration)")
    }

    fun listAssetForSale(
        asset: AssetInfo,
        price: ULong,
        royaltyPercent: UInt,
        expiryBlock: ULong?
    ) {
        viewModelScope.launch {
            when (val result = repository.listAssetForSale(asset, price, royaltyPercent, expiryBlock)) {
                is WalletResult.Success -> {
                    refreshListings()
                    _uiState.value = _uiState.value.copy(error = null)
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
            }
        }
    }

    fun cancelListing(listingId: String) {
        viewModelScope.launch {
            when (val result = repository.cancelListing(listingId)) {
                is WalletResult.Success -> {
                    refreshListings()
                    _uiState.value = _uiState.value.copy(error = null)
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
            }
        }
    }

    fun buyAsset(listingId: String) {
        viewModelScope.launch {
            when (val result = repository.buyAsset(listingId)) {
                is WalletResult.Success -> {
                    refreshListings()
                    refreshBalance()
                    _uiState.value = _uiState.value.copy(error = null)
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
            }
        }
    }

    private fun refreshListings() {
        val activeListings = listingService.getActiveListings()
        val currentLock = _uiState.value.currentAccount?.addresses?.firstOrNull()?.lockScript
        val myListings = if (currentLock != null) {
            listingService.getListingsBySeller(currentLock)
        } else {
            emptyList()
        }

        _uiState.value = _uiState.value.copy(
            activeListings = activeListings,
            myListings = myListings
        )
    }

    fun addContact(address: String, name: String) {
        val contact = ContactProfile(
            address = address,
            name = name,
            publicKey = null,
            discovered = false
        )
        contactBook.addContact(contact)
        refreshMessaging()
    }

    fun removeContact(address: String) {
        contactBook.removeContact(address)
        refreshMessaging()
    }

    fun createConversation(address: String) {
        val ownerAddress = _uiState.value.currentAccount?.addresses?.firstOrNull()?.bech32m ?: return
        val contact = contactBook.getContact(address)
        val conversation = Conversation(
            contactAddress = address,
            contactName = contact?.name,
            messages = emptyList(),
            lastMessage = null,
            unreadCount = 0
        )

        val currentConversations = _uiState.value.conversations.toMutableList()
        if (currentConversations.none { it.contactAddress == address }) {
            currentConversations.add(0, conversation)
            _uiState.value = _uiState.value.copy(conversations = currentConversations)
        }
    }

    fun sendMessage(toAddress: String, content: ByteArray) {
        val ownerAddress = _uiState.value.currentAccount?.addresses?.firstOrNull()?.bech32m ?: return

        viewModelScope.launch {
            try {
                messagingService.sendMessage(ownerAddress, toAddress, content)
                refreshMessaging()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to send message: ${e.message}")
            }
        }
    }

    fun refreshMessaging() {
        val ownerAddress = _uiState.value.currentAccount?.addresses?.firstOrNull()?.bech32m ?: return

        val contacts = contactBook.getAllContacts()
        val conversations = messagingService.getConversations(ownerAddress)

        _uiState.value = _uiState.value.copy(
            contacts = contacts,
            conversations = conversations
        )
    }

    fun scanNotifications() {
        val ownerAddress = _uiState.value.currentAccount?.addresses?.firstOrNull()?.bech32m ?: return

        viewModelScope.launch {
            try {
                messagingService.scanNotifications(ownerAddress)
                refreshMessaging()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to scan notifications: ${e.message}")
            }
        }
    }

    fun refreshDaoDeposits() {
        val account = _uiState.value.currentAccount ?: return
        val lockScript = account.addresses.firstOrNull()?.lockScript ?: return
        val network = _uiState.value.currentNetwork

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDao = true, daoError = null)
            when (val result = repository.scanDaoDeposits(lockScript, network)) {
                is WalletResult.Success -> {
                    val deposits = result.data
                    val overview = repository.getDaoOverview(deposits)
                    _uiState.value = _uiState.value.copy(
                        daoDeposits = deposits,
                        daoOverview = overview,
                        isLoadingDao = false
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoadingDao = false,
                        daoError = result.message
                    )
                }
            }
        }
    }

    fun selectDaoTab(tab: DaoTab) {
        _uiState.value = _uiState.value.copy(daoSelectedTab = tab)
    }

    fun depositDao(amountCkb: ULong) {
        val account = _uiState.value.currentAccount ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.depositDao(account, amountCkb)) {
                is WalletResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        lastTxHash = result.data,
                        pendingDaoTxHash = result.data,
                        pendingDaoAction = "deposit"
                    )
                    refreshBalance()
                    refreshDaoDeposits()
                    pollDaoTxConfirmation(result.data)
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun withdrawDaoPhase1(deposit: DaoDeposit) {
        val account = _uiState.value.currentAccount ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.withdrawDaoPhase1(account, deposit)) {
                is WalletResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        lastTxHash = result.data,
                        pendingDaoTxHash = result.data,
                        pendingDaoAction = "withdraw"
                    )
                    refreshBalance()
                    refreshDaoDeposits()
                    pollDaoTxConfirmation(result.data)
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    private fun pollDaoTxConfirmation(txHash: String) {
        viewModelScope.launch {
            repeat(12) { attempt ->
                delay(10_000L)
                val confirmed = repository.getTransactionStatus(txHash)
                if (confirmed is WalletResult.Success && confirmed.data == TxStatus.CONFIRMED) {
                    _uiState.value = _uiState.value.copy(
                        pendingDaoTxHash = null,
                        pendingDaoAction = null
                    )
                    refreshDaoDeposits()
                    refreshBalance()
                    return@launch
                }
            }
            refreshDaoDeposits()
            _uiState.value = _uiState.value.copy(
                pendingDaoTxHash = null,
                pendingDaoAction = null
            )
        }
    }

    fun unlockDao(deposit: DaoDeposit) {
        val account = _uiState.value.currentAccount ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.claimDao(account, deposit)) {
                is WalletResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        lastTxHash = result.data,
                        pendingDaoTxHash = result.data,
                        pendingDaoAction = "unlock"
                    )
                    refreshBalance()
                    refreshDaoDeposits()
                    pollDaoTxConfirmation(result.data)
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun setTheme(theme: ThemeConfig) {
        skinManager.setTheme(theme)
        _uiState.value = _uiState.value.copy(currentTheme = theme)
    }

    fun saveCustomTheme(theme: ThemeConfig) {
        skinManager.saveCustomTheme(theme)
        _uiState.value = _uiState.value.copy(
            customThemes = skinManager.getCustomThemes()
        )
    }

    fun removeCustomTheme(themeName: String) {
        skinManager.removeCustomTheme(themeName)
        _uiState.value = _uiState.value.copy(
            customThemes = skinManager.getCustomThemes()
        )
    }

    fun exportThemeJson(theme: ThemeConfig): String {
        return skinManager.exportThemeJson(theme)
    }

    fun importTheme(jsonString: String) {
        val theme = skinManager.importThemeJson(jsonString)
        if (theme != null) {
            skinManager.saveCustomTheme(theme)
            _uiState.value = _uiState.value.copy(
                customThemes = skinManager.getCustomThemes()
            )
        }
    }

    fun updatePanelBackground(panelName: String, imageUri: String) {
        val currentTheme = _uiState.value.currentTheme ?: return
        val panelBackground = com.wyltek.wallet.core.skin.PanelBackground(
            imageUri = imageUri
        )
        skinManager.updatePanelBackground(panelName, panelBackground)
        _uiState.value = _uiState.value.copy(
            currentTheme = skinManager.getCurrentTheme()
        )
    }

    fun createPasskey(name: String) {
        val address = _uiState.value.currentAccount?.addresses?.firstOrNull()?.bech32m ?: return

        val passkey = passkeyManager.createCredential(name, address)
        _uiState.value = _uiState.value.copy(
            passkeyCredentials = passkeyManager.getAllCredentials()
        )
    }

    fun deletePasskey(id: String) {
        passkeyManager.deleteCredential(id)
        _uiState.value = _uiState.value.copy(
            passkeyCredentials = passkeyManager.getAllCredentials()
        )
    }

    fun linkJoyIDAccount(address: String, pubkeyHash: String, name: String?) {
        passkeyManager.linkJoyIDAccount(address, pubkeyHash, name)
        _uiState.value = _uiState.value.copy(
            joyIdAccounts = passkeyManager.getAllJoyIDAccounts()
        )
    }

    fun unlinkJoyIDAccount(address: String) {
        passkeyManager.unlinkJoyIDAccount(address)
        _uiState.value = _uiState.value.copy(
            joyIdAccounts = passkeyManager.getAllJoyIDAccounts()
        )
    }

    fun getJoyIDSignURL(message: String, callbackUrl: String): String {
        return joyIdIntegration.buildJoyIDSignMessageURL(message, callbackUrl)
    }

    fun importWatchOnlyAccount(name: String, xpub: String, path: String) {
        val account = watchOnlyManager.importXpub(name, xpub, path)
        _uiState.value = _uiState.value.copy(
            watchOnlyAccounts = watchOnlyManager.getAllAccounts()
        )
    }

    fun deleteWatchOnlyAccount(id: String) {
        watchOnlyManager.deleteAccount(id)
        _uiState.value = _uiState.value.copy(
            watchOnlyAccounts = watchOnlyManager.getAllAccounts()
        )
    }

    fun refreshWatchOnlyAddresses(id: String) {
        watchOnlyManager.refreshAddresses(id)
        _uiState.value = _uiState.value.copy(
            watchOnlyAccounts = watchOnlyManager.getAllAccounts()
        )
    }

    fun enableStrongBox() {
        val success = strongBoxManager.createStrongBoxKey()
        _uiState.value = _uiState.value.copy(
            securityInfo = strongBoxManager.getSecurityInfo(),
            error = if (!success) "Failed to enable StrongBox" else null
        )
    }

    fun disableStrongBox() {
        strongBoxManager.deleteKey()
        _uiState.value = _uiState.value.copy(
            securityInfo = strongBoxManager.getSecurityInfo()
        )
    }

    fun refreshRpcHealth() {
        viewModelScope.launch {
            val chainManager = repository.getChainManager()
            val providers = chainManager.getAllProviders()

            val statuses = providers.map { provider ->
                val status = chainManager.checkProviderHealth(provider)
                status
            }

            _uiState.value = _uiState.value.copy(rpcHealthStatuses = statuses)
        }
    }

    fun wrapPrivateKey(privateKey: ByteArray): ByteArray? {
        return strongBoxManager.wrapPrivateKey(privateKey)
    }

    fun unwrapPrivateKey(wrappedKey: ByteArray): ByteArray? {
        return strongBoxManager.unwrapPrivateKey(wrappedKey)
    }

    fun importCustomToken(codeHash: String, hashType: String, args: String, symbol: String): Boolean {
        val store = repository.getCustomTokenStore()
        val token = CustomTokenDefinition(
            codeHash = codeHash,
            hashType = hashType,
            args = args,
            symbol = symbol
        )
        val added = store.addToken(token)
        if (added) {
            // Refresh token balances to include the newly imported token
            viewModelScope.launch {
                val account = _uiState.value.currentAccount ?: return@launch
                val lockScript = account.addresses.firstOrNull()?.lockScript ?: return@launch
                when (val result = repository.scanTokens(lockScript, account.network)) {
                    is WalletResult.Success -> {
                        _uiState.value = _uiState.value.copy(tokenBalances = result.data)
                    }
                    else -> { /* ignore */ }
                }
            }
        }
        return added
    }

    fun getCustomTokens(): List<CustomTokenDefinition> {
        return repository.getCustomTokenStore().getAllTokens()
    }

    fun switchNetwork(network: NetworkType) {
        repository.setCurrentNetwork(network)
        _uiState.value = _uiState.value.copy(
            currentNetwork = network,
            activeRpc = repository.getActiveRpcName(),
            balance = "0.00 CKB",
            tokenBalances = emptyList(),
            allAssets = emptyList(),
            sporeAssets = emptyList(),
            cotaAssets = emptyList(),
            ckbfsAssets = emptyList(),
            transactionHistory = emptyList(),
            daoDeposits = emptyList(),
            daoOverview = DaoOverview(),
            daoError = null
        )
        refreshBalance()
        refreshAssets()
        refreshDaoDeposits()
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoSync()
    }
}
