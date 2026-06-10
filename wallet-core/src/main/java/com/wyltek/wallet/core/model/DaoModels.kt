package com.wyltek.wallet.core.model

enum class DaoCellStatus {
    DEPOSITING,
    DEPOSITED,
    WITHDRAWING,
    LOCKED,
    UNLOCKABLE,
    UNLOCKING,
    COMPLETED
}

data class EpochInfo(
    val number: Long,
    val index: Long,
    val length: Long
) {
    val value: Double get() = number + index.toDouble() / length

    companion object {
        fun fromHex(epochHex: String): EpochInfo {
            val epoch = epochHex.removePrefix("0x").toLong(16)
            val number = epoch and 0xFFFFFFL
            val index = (epoch shr 24) and 0xFFFFL
            val length = (epoch shr 40) and 0xFFFFL
            return EpochInfo(number = number, index = index, length = length)
        }
    }
}

enum class CyclePhase {
    NORMAL,
    SUGGESTED,
    ENDING
}

fun cyclePhaseFromProgress(progress: Float): CyclePhase = when {
    progress >= 0.95f -> CyclePhase.ENDING
    progress >= 0.80f -> CyclePhase.SUGGESTED
    else -> CyclePhase.NORMAL
}

data class DaoDeposit(
    val outPoint: OutPoint,
    val capacity: ULong,
    val status: DaoCellStatus,
    val depositBlockNumber: ULong,
    val depositBlockHash: String = "",
    val depositEpoch: EpochInfo? = null,
    val depositAr: ULong = 0u,
    val withdrawBlockNumber: ULong? = null,
    val withdrawBlockHash: String? = null,
    val withdrawEpoch: EpochInfo? = null,
    val withdrawAr: ULong = 0u,
    val compensation: ULong = 0u,
    val unlockEpoch: EpochInfo? = null,
    val lockRemainingEpochs: Long = 0L,
    val compensationCycleProgress: Float = 0f,
    val cyclePhase: CyclePhase = CyclePhase.NORMAL,
    val depositTimestamp: ULong = 0u,
    val apc: Double = 0.0
)

data class DaoOverview(
    val totalDeposited: ULong = 0u,
    val totalCompensation: ULong = 0u,
    val currentApc: Double = 0.0,
    val activeCount: Int = 0,
    val completedCount: Int = 0
)

enum class DaoTab { ACTIVE, COMPLETED }

sealed class DaoAction {
    data class Deposit(val amountCkb: Double) : DaoAction()
    data class Withdraw(val outPoint: OutPoint) : DaoAction()
    data class Unlock(val outPoint: OutPoint) : DaoAction()
}

data class DaoCellData(
    val isDeposited: Boolean,
    val depositBlockNumber: ULong? = null
)

fun parseDaoCellData(outputData: String): DaoCellData {
    val clean = outputData.removePrefix("0x")
    if (clean.length != 16) return DaoCellData(isDeposited = false)
    if (clean.all { it == '0' }) return DaoCellData(isDeposited = true)
    val blockNumber = clean.lowercase().chunked(2).reversed().joinToString("").toULong(16)
    return DaoCellData(isDeposited = false, depositBlockNumber = blockNumber)
}

fun computeDaoCompensation(
    totalCapacity: ULong,
    occupiedCapacity: ULong,
    depositAr: ULong,
    targetAr: ULong
): ULong {
    if (depositAr == 0uL) return 0uL
    if (targetAr < depositAr) return 0uL
    val countedCapacity = totalCapacity - occupiedCapacity
    return (countedCapacity.toLong() * (targetAr.toLong() - depositAr.toLong()) / depositAr.toLong()).toULong()
}

fun computeMaxWithdrawable(
    totalCapacity: ULong,
    occupiedCapacity: ULong,
    depositAr: ULong,
    targetAr: ULong
): ULong {
    val compensation = computeDaoCompensation(totalCapacity, occupiedCapacity, depositAr, targetAr)
    return totalCapacity + compensation
}

fun determineDaoStatus(
    isWithdrawingCell: Boolean,
    hasPendingWithdraw: Boolean,
    hasPendingUnlock: Boolean,
    hasPendingDeposit: Boolean,
    currentEpoch: EpochInfo?,
    unlockEpoch: EpochInfo?
): DaoCellStatus = when {
    hasPendingUnlock -> DaoCellStatus.UNLOCKING
    isWithdrawingCell && unlockEpoch != null && currentEpoch != null
        && currentEpoch.value >= unlockEpoch.value -> DaoCellStatus.UNLOCKABLE
    isWithdrawingCell -> DaoCellStatus.LOCKED
    hasPendingWithdraw -> DaoCellStatus.WITHDRAWING
    hasPendingDeposit -> DaoCellStatus.DEPOSITING
    else -> DaoCellStatus.DEPOSITED
}