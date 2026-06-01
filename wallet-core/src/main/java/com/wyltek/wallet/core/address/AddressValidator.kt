package com.wyltek.wallet.core.address

import com.wyltek.wallet.core.model.*

object AddressValidator {

    private const val BECH32_MAINNET_HRP = "ckb"
    private const val BECH32_TESTNET_HRP = "ckt"
    private const val BECH32_DEVNET_HRP = "ckd"

    private val VALID_HRPS = setOf(
        BECH32_MAINNET_HRP,
        BECH32_TESTNET_HRP,
        BECH32_DEVNET_HRP
    )

    fun validate(address: String): AddressValidationResult {
        if (address.isBlank()) {
            return AddressValidationResult.Invalid("Address is empty")
        }

        return when {
            address.startsWith("ckb1") || address.startsWith("ckt1") || address.startsWith("ckd1") -> {
                validateBech32m(address)
            }
            address.startsWith("CKB") || address.startsWith("CKT") -> {
                validateBase32(address)
            }
            address.startsWith("0x") -> {
                validateRawLockArgs(address)
            }
            else -> AddressValidationResult.Invalid("Unrecognised address format")
        }
    }

    private fun validateBech32m(address: String): AddressValidationResult {
        val parts = address.split("1", limit = 2)
        if (parts.size != 2) {
            return AddressValidationResult.Invalid("Invalid bech32m format: missing separator")
        }

        val hrp = parts[0]
        val data = parts[1]

        if (hrp !in VALID_HRPS) {
            return AddressValidationResult.Invalid("Unknown HRP: $hrp")
        }

        val network = when (hrp) {
            BECH32_MAINNET_HRP -> NetworkType.MAINNET
            BECH32_TESTNET_HRP -> NetworkType.TESTNET
            BECH32_DEVNET_HRP -> NetworkType.DEVNET
            else -> return AddressValidationResult.Invalid("Unknown network: $hrp")
        }

        if (data.isEmpty()) {
            return AddressValidationResult.Invalid("Empty data part")
        }

        return AddressValidationResult.Valid(
            network = network,
            formatVersion = AddressFormatVersion.CKB2021,
            humanReadable = "$hrp:..."
        )
    }

    private fun validateBase32(address: String): AddressValidationResult {
        val hrp = if (address.startsWith("CKB")) "ckb" else "ckt"
        val network = if (hrp == "ckb") NetworkType.MAINNET else NetworkType.TESTNET

        return AddressValidationResult.Valid(
            network = network,
            formatVersion = AddressFormatVersion.CKB2019,
            humanReadable = "$hrp (deprecated base32)..."
        )
    }

    private fun validateRawLockArgs(address: String): AddressValidationResult {
        val hex = address.removePrefix("0x")
        if (hex.length != 42 && hex.length != 66) {
            return AddressValidationResult.Invalid(
                "Raw lock args must be 20 or 32 bytes (got ${hex.length / 2} bytes)"
            )
        }
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return AddressValidationResult.Invalid("Invalid hex characters")
        }
        return AddressValidationResult.Valid(
            network = NetworkType.TESTNET,
            formatVersion = AddressFormatVersion.UNKNOWN,
            humanReadable = "Raw lock args..."
        )
    }

    fun detectNetwork(address: String): NetworkType? {
        return when {
            address.startsWith("ckb1") || address.startsWith("CKB") -> NetworkType.MAINNET
            address.startsWith("ckt1") || address.startsWith("CKT") -> NetworkType.TESTNET
            address.startsWith("ckd1") -> NetworkType.DEVNET
            else -> null
        }
    }
}

sealed class AddressValidationResult {
    data class Valid(
        val network: NetworkType,
        val formatVersion: AddressFormatVersion,
        val humanReadable: String
    ) : AddressValidationResult()

    data class Invalid(val reason: String) : AddressValidationResult()
}
