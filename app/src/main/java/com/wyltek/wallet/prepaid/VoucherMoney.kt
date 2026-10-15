package com.wyltek.wallet.prepaid

import java.math.BigDecimal

/** Shop-currency prices in minor units (vouchers spec §4: issue_price u32). Zero-decimal currencies per ISO 4217. */
object VoucherMoney {
    private val ZERO_DECIMAL = setOf("JPY", "KRW", "VND", "CLP", "ISK", "UGX")
    fun exponent(currency: String) = if (currency in ZERO_DECIMAL) 0 else 2
    fun parse(text: String, currency: String): Long? {
        val d = text.trim().toBigDecimalOrNull() ?: return null
        if (d.signum() < 0 || d.scale() > exponent(currency)) return null
        val minor = d.movePointRight(exponent(currency))
        return minor.toLong().takeIf { minor <= BigDecimal(4_294_967_295L) }
    }
    fun format(minor: Long, currency: String): String = BigDecimal(minor).movePointLeft(exponent(currency)).toPlainString() + " " + currency
}
