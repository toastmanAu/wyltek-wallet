package com.wyltek.wallet.core.transaction

import com.wyltek.wallet.core.model.*

class TransactionBuilder {

    fun buildTransaction(
        inputs: List<Utxo>,
        outputs: List<CellOutput>,
        cellDeps: List<CellDep> = emptyList(),
        headerDeps: List<String> = emptyList(),
        feeRate: ULong = 1000u
    ): Transaction {
        val cellInputs = inputs.map { utxo ->
            CellInput(
                previousOutput = utxo.outPoint,
                since = 0u
            )
        }

        val totalInputCapacity = inputs.sumOf { it.capacity }
        val totalOutputCapacity = outputs.sumOf { it.capacity }
        val fee = calculateFee(inputs.size, outputs.size, feeRate)

        if (totalInputCapacity < totalOutputCapacity + fee) {
            throw InsufficientCapacityException(
                "Input capacity ${totalInputCapacity} < output + fee ${totalOutputCapacity + fee}"
            )
        }

        return Transaction(
            version = 0u,
            cellDeps = cellDeps,
            headerDeps = headerDeps,
            inputs = cellInputs,
            outputs = outputs,
            outputsData = outputs.map { "0x" },
            witnesses = inputs.map { "0x" }
        )
    }

    fun calculateFee(
        inputCount: Int,
        outputCount: Int,
        feeRate: ULong
    ): ULong {
        val estimatedSize = (inputCount * 44 + outputCount * 68 + 4).toULong()
        return estimatedSize * feeRate / 1000u
    }

    fun buildInternalTransfer(
        fromUtxos: List<Utxo>,
        toAddress: String,
        amount: ULong,
        feeRate: ULong = 1000u
    ): Transaction {
        val totalCapacity = fromUtxos.sumOf { it.capacity }

        if (amount > totalCapacity) {
            throw InsufficientCapacityException(
                "Requested $amount but only $totalCapacity available"
            )
        }

        val fee = calculateFee(fromUtxos.size, 1, feeRate)

        val output = CellOutput(
            capacity = amount,
            lock = LockScript(
                codeHash = "",
                hashType = "data",
                args = toAddress
            )
        )

        return buildTransaction(
            inputs = fromUtxos,
            outputs = listOf(output),
            feeRate = feeRate
        )
    }
}

class InsufficientCapacityException(message: String) : Exception(message)
