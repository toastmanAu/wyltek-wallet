package com.wyltek.wallet.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val activeRpc: String? = null
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WalletRepository(application)

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        loadAccounts()
        _uiState.value = _uiState.value.copy(activeRpc = repository.getActiveRpcName())
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

    fun startAutoSync(intervalMs: Long = 30_000L) {
        stopAutoSync()
        syncJob = viewModelScope.launch {
            while (true) {
                syncTipHeader()
                refreshBalance()
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

    override fun onCleared() {
        super.onCleared()
        stopAutoSync()
    }
}
