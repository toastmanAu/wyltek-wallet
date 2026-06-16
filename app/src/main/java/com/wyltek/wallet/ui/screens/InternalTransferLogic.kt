package com.wyltek.wallet.ui.screens

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Pure decision logic for the internal transfer screen — no Android / Compose
 * dependencies so it is unit-testable on the JVM. The screen is a thin shell
 * over these functions plus the verified sendCkb / sendToken ViewModel methods.
 */
object InternalTransferLogic {

    enum class Direction { CLASSIC_TO_PQ, PQ_TO_CLASSIC }

    /** sUDT sends from a PQ source lock are not yet supported, so tokens are
     *  CKB-only when spending from the PQ lock. */
    fun tokensEnabled(sourceIsPq: Boolean): Boolean = !sourceIsPq

    private val SHANNONS_PER_CKB = BigDecimal("100000000")
    private val ULONG_MAX = BigInteger("18446744073709551615")

    /**
     * Parse a CKB decimal string to shannons. Returns null if blank, not a
     * number, non-positive, finer than 8 decimal places (sub-shannon), or
     * beyond ULong range.
     */
    fun parseCkbToShannons(input: String): ULong? {
        val trimmed = input.trim()
        // Reject scientific notation (e.g. "1e2"): an amount field expects plain
        // decimal strings, and e-notation produces negative BigDecimal.scale()
        // that would slip past the 8-dp precision guard below.
        if (trimmed.contains('e', ignoreCase = true)) return null
        val ckb = trimmed.toBigDecimalOrNull() ?: return null
        if (ckb <= BigDecimal.ZERO) return null
        if (ckb.scale() > 8) return null
        // toBigIntegerExact() is safe here: the scale<=8 guard plus the 1e8
        // multiply guarantee the product is always an exact integer.
        val shannons = ckb.multiply(SHANNONS_PER_CKB).toBigIntegerExact()
        if (shannons > ULONG_MAX) return null
        return shannons.toString().toULong()
    }

    /** Parse a raw integer token-units string. Null if blank, not an integer,
     *  or non-positive. */
    fun parseTokenUnits(input: String): BigInteger? {
        val units = input.trim().toBigIntegerOrNull() ?: return null
        if (units <= BigInteger.ZERO) return null
        return units
    }

    fun ckbAmountWithinBalance(shannons: ULong, balanceShannons: ULong): Boolean =
        shannons <= balanceShannons

    fun tokenAmountWithinBalance(units: BigInteger, balanceUnits: BigInteger): Boolean =
        units <= balanceUnits
}
