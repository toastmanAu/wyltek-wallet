package com.wyltek.wallet.core.chain

import android.util.Log
import com.wyltek.wallet.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DaoProvider"

class DaoProvider(private val chainManager: ChainManager) {

    suspend fun scanDaoDeposits(
        lockScript: LockScript,
        network: NetworkType
    ): List<DaoDeposit> = withContext(Dispatchers.IO) {
        val deposits = mutableListOf<DaoDeposit>()

        try {
            val daoTypeScript = daoTypeScript(network)

            val daoCells = chainManager.getCellsByLockAndType(lockScript, daoTypeScript)

            Log.d(TAG, "scanDaoDeposits: found ${daoCells.size} DAO cells for lock=$lockScript")

            if (daoCells.isEmpty()) return@withContext emptyList()

            val tipHeader = chainManager.getTipHeader()
            val tipEpoch = tipHeader?.epoch?.let { EpochInfo.fromHex(it) }
            val tipAr = parseArFromDaoField(tipHeader?.dao)

            for (cell in daoCells) {
                try {
                    val cellData = parseDaoCellData(cell.data ?: "")
                    val capacity = cell.capacity
                    val occupiedCapacity = computeOccupiedCapacity(cell)

                    val isWithdrawingCell = !cellData.isDeposited

                    // Block-number sourcing differs by cell phase:
                    //  - deposit cell  : the cell's OWN block is the deposit block; no withdraw block yet.
                    //  - withdrawing cell: the cell's OWN block is the WITHDRAW block (W); the deposit
                    //    block (D) is recorded in the cell's data (8-byte LE block number).
                    // The DAO lock period (since ≥ deposit_epoch + 180) is measured from the DEPOSIT
                    // block, so D/W must not be swapped — this is the source of truth for unlockEpoch.
                    val depositBlockNumber = if (isWithdrawingCell) {
                        cellData.depositBlockNumber ?: cell.blockNumber
                    } else {
                        cell.blockNumber
                    }
                    val withdrawBlockNumber = if (isWithdrawingCell) cell.blockNumber else null

                    val depositHeader = chainManager.getHeaderByNumber("0x${depositBlockNumber.toString(16)}")
                    val depositEpoch = depositHeader?.epoch?.let { EpochInfo.fromHex(it) }
                    val depositAr = parseArFromDaoField(depositHeader?.dao)

                    var status = DaoCellStatus.DEPOSITED
                    var compensation: ULong = 0u
                    var targetAr: ULong = tipAr
                    var unlockEpoch: EpochInfo? = null
                    var lockRemainingEpochs: Long = 0L
                    var apc: Double = 0.0
                    var withdrawBlockHash: String? = null
                    var withdrawEpoch: EpochInfo? = null
                    var withdrawAr: ULong = 0u

                    if (isWithdrawingCell && withdrawBlockNumber != null) {
                        val withdrawHeader = chainManager.getHeaderByNumber("0x${withdrawBlockNumber.toString(16)}")
                        withdrawBlockHash = withdrawHeader?.hash
                        withdrawEpoch = withdrawHeader?.epoch?.let { EpochInfo.fromHex(it) }
                        withdrawAr = parseArFromDaoField(withdrawHeader?.dao).takeIf { it > 0u } ?: depositAr

                        // Compensation freezes at the withdraw block's AR.
                        targetAr = withdrawAr

                        if (depositEpoch != null) {
                            val unlockEpochNumber = depositEpoch.number + 180
                            unlockEpoch = EpochInfo(
                                number = unlockEpochNumber,
                                index = depositEpoch.index,
                                length = depositEpoch.length
                            )
                            if (tipEpoch != null) {
                                lockRemainingEpochs = (unlockEpochNumber - tipEpoch.number).coerceAtLeast(0)
                            }
                        }

                        compensation = computeDaoCompensation(capacity, occupiedCapacity, depositAr, targetAr)

                        status = if (tipEpoch != null && unlockEpoch != null) {
                            if (tipEpoch.value >= unlockEpoch.value) DaoCellStatus.UNLOCKABLE
                            else DaoCellStatus.LOCKED
                        } else {
                            DaoCellStatus.LOCKED
                        }
                    } else {
                        compensation = computeDaoCompensation(capacity, occupiedCapacity, depositAr, targetAr)
                        if (depositEpoch != null && depositAr > 0u && targetAr > depositAr && tipEpoch != null) {
                            val epochsElapsed = (tipEpoch.number - depositEpoch.number).coerceAtLeast(0)
                            val secondsPerEpoch = 14_400.0
                            val yearsElapsed = (epochsElapsed * secondsPerEpoch) / (365.25 * 24 * 3600)
                            if (yearsElapsed > 0) {
                                val arRatio = targetAr.toDouble() / depositAr.toDouble()
                                apc = ((arRatio - 1.0) / yearsElapsed) * 100.0
                            }
                        }
                    }

                    val cycleProgress = if (isWithdrawingCell && unlockEpoch != null && depositEpoch != null && tipEpoch != null) {
                        val totalEpochs = 180.0
                        val elapsedEpochs = (tipEpoch.number - depositEpoch.number).coerceAtLeast(0).toDouble()
                        (elapsedEpochs / totalEpochs).toFloat().coerceIn(0f, 1f)
                    } else 0f

                    deposits.add(
                        DaoDeposit(
                            outPoint = cell.outPoint,
                            capacity = capacity,
                            status = status,
                            depositBlockNumber = depositBlockNumber,
                            depositBlockHash = depositHeader?.hash ?: "",
                            depositEpoch = depositEpoch,
                            depositAr = depositAr,
                            withdrawBlockNumber = withdrawBlockNumber,
                            withdrawBlockHash = withdrawBlockHash,
                            withdrawEpoch = withdrawEpoch,
                            withdrawAr = withdrawAr,
                            compensation = compensation,
                            unlockEpoch = unlockEpoch,
                            lockRemainingEpochs = lockRemainingEpochs,
                            compensationCycleProgress = cycleProgress,
                            cyclePhase = cyclePhaseFromProgress(cycleProgress),
                            depositTimestamp = depositHeader?.timestamp?.toLong()?.toULong() ?: 0u,
                            apc = apc
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process DAO cell: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanDaoDeposits failed: ${e.message}")
        }

        return@withContext deposits
    }

    suspend fun getDaoOverview(deposits: List<DaoDeposit>): DaoOverview {
        val active = deposits.filter { it.status != DaoCellStatus.COMPLETED }
        val completed = deposits.filter { it.status == DaoCellStatus.COMPLETED }

        return DaoOverview(
            totalDeposited = active.sumOf { it.capacity.toLong() }.toULong(),
            totalCompensation = active.sumOf { it.compensation.toLong() }.toULong(),
            currentApc = if (active.isNotEmpty()) active.map { it.apc }.average() else 0.0,
            activeCount = active.size,
            completedCount = completed.size
        )
    }

    companion object {
        fun daoTypeScript(network: NetworkType): LockScript {
            val config = NetworkConfig.forNetwork(network)
            return LockScript(
                codeHash = config.daoTypeCodeHash,
                hashType = config.daoTypeHashType,
                args = "0x"
            )
        }

        fun parseArFromDaoField(daoField: String?): ULong {
            if (daoField.isNullOrEmpty()) return 0u
            val clean = daoField.removePrefix("0x")
            if (clean.length < 32) return 0u
            return try {
                clean.substring(0, 16).toULong(16)
            } catch (e: Exception) {
                0u
            }
        }

        fun computeOccupiedCapacity(cell: Utxo): ULong {
            val SHANNONS_PER_BYTE = 100_000_000uL
            val CAPACITY_FIELD_BYTES = 8uL
            val CODE_HASH_BYTES = 32uL
            val HASH_TYPE_BYTES = 1uL

            val lockArgsBytes = (cell.lock.args.removePrefix("0x").length / 2).toULong()
            val typeBytes = cell.type_?.let {
                CODE_HASH_BYTES + HASH_TYPE_BYTES + (it.args.removePrefix("0x").length / 2).toULong()
            } ?: 0u
            val dataBytes = (cell.data?.removePrefix("0x")?.length?.div(2) ?: 0).toULong()

            val totalBytes = CAPACITY_FIELD_BYTES + CODE_HASH_BYTES + HASH_TYPE_BYTES + lockArgsBytes + typeBytes + dataBytes
            return totalBytes * SHANNONS_PER_BYTE
        }
    }
}