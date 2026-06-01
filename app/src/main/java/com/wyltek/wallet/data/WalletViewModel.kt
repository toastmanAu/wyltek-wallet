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
import com.wyltek.wallet.core.model.*
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
    val myListings: List<Listing> = emptyList()
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WalletRepository(application)
    private val listingService = repository.getListingService()

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        loadAccounts()
        _uiState.value = _uiState.value.copy(activeRpc = repository.getActiveRpcName())
        refreshListings()
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

    override fun onCleared() {
        super.onCleared()
        stopAutoSync()
    }
}
