package com.wyltek.wallet.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wyltek.wallet.core.assets.AssetInfo
import com.wyltek.wallet.core.assets.AssetType
import com.wyltek.wallet.core.assets.Listing
import com.wyltek.wallet.core.chain.CellsCapacity
import com.wyltek.wallet.core.chain.HeaderInfo
import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.messaging.ContactProfile
import com.wyltek.wallet.core.messaging.Conversation
import com.wyltek.wallet.core.model.*
import com.wyltek.wallet.core.passkey.JoyIDAccount
import com.wyltek.wallet.core.passkey.PasskeyCredential
import com.wyltek.wallet.core.skin.ThemeConfig
import com.wyltek.wallet.core.skin.ThemePresets
import com.wyltek.wallet.core.watchonly.WatchOnlyAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val watchOnlyAccounts: List<WatchOnlyAccount> = emptyList()
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
            watchOnlyAccounts = watchOnlyManager.getAllAccounts()
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
        val account = _uiState.value.currentAccount ?: return
        val lockScript = account.addresses.firstOrNull()?.lockScript ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)

            when (val result = repository.refreshBalance(lockScript)) {
                is WalletResult.Success -> {
                    val capacity = result.data
                    val ckb = capacity.totalCapacity / 100_000_000u
                    val remainder = capacity.totalCapacity % 100_000_000u
                    val fraction = remainder.toString().padStart(8, '0').trimEnd('0')

                    _uiState.value = _uiState.value.copy(
                        balance = if (fraction.isNotEmpty()) "$ckb.$fraction CKB" else "$ckb CKB",
                        balanceCkb = capacity.totalCapacity,
                        occupiedCapacity = capacity.occupiedCapacity,
                        isSyncing = false,
                        error = null
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun syncTipHeader() {
        viewModelScope.launch {
            when (val result = repository.getTipHeader()) {
                is WalletResult.Success -> {
                    val header = result.data
                    _uiState.value = _uiState.value.copy(
                        tipBlockNumber = header.number.toLong(),
                        isConnected = true
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(isConnected = false)
                }
            }
        }
    }

    fun refreshAssets() {
        val account = _uiState.value.currentAccount ?: return
        val lockScript = account.addresses.firstOrNull()?.lockScript ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanningAssets = true)

            when (val result = repository.scanAssets(lockScript)) {
                is WalletResult.Success -> {
                    val assets = result.data
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
                        error = result.message
                    )
                }
            }
        }
    }

    fun selectAsset(asset: AssetInfo) {
        _uiState.value = _uiState.value.copy(selectedAsset = asset)
    }

    fun clearSelectedAsset() {
        _uiState.value = _uiState.value.copy(selectedAsset = null)
    }

    fun startAutoSync(intervalMs: Long = 30_000L) {
        stopAutoSync()
        syncJob = viewModelScope.launch {
            while (true) {
                syncTipHeader()
                refreshBalance()
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = repository.createWallet(name, type)) {
                is WalletResult.Success -> {
                    val account = result.data
                    _uiState.value = _uiState.value.copy(
                        accounts = _uiState.value.accounts + account,
                        currentAccount = account,
                        isLoading = false,
                        balance = "0.00 CKB",
                        createdMnemonic = repository.getWalletSeed(account.id)
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

    fun importWallet(name: String, mnemonic: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = repository.importWallet(name, mnemonic)) {
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

    fun buildTransaction(toAddress: String, amount: ULong) {
        viewModelScope.launch {
            val from = _uiState.value.currentAccount ?: return@launch
            val fromAddress = from.addresses.firstOrNull()?.bech32m ?: return@launch

            when (val result = repository.buildTransaction(fromAddress, toAddress, amount)) {
                is WalletResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        lastTxHash = result.data,
                        error = null
                    )
                }
                is WalletResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
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

    override fun onCleared() {
        super.onCleared()
        stopAutoSync()
    }
}
