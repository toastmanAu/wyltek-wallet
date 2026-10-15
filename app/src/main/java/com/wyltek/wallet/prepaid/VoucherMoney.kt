package com.wyltek.wallet.prepaid

import java.math.BigDecimal

/** Shop-currency prices in minor units (vouchers spec §4: issue_price u32). Zero-decimal currencies per ISO 4217. */
object VoucherMoney {
    private val ZERO_DECIMAL = setOf("JPY", "KRW", "VND", "CLP", "ISK", "UGX")
    fun exponent(currency: String) = if (currency in ZERO_DECIMAL) 0 else 2
    private val PLAIN = Regex("""\d{1,12}(\.\d{1,12})?""")
    private val MAX_MINOR = BigDecimal(4_294_967_295L)

    /** Plain decimal only ("4.50", "4.500", "500"): no sign, no exponent, no grouping. Never throws; anything else is null. */
    fun parse(text: String, currency: String): Long? {
        val t = text.trim()
        if (!PLAIN.matches(t)) return null
        val d = t.toBigDecimalOrNull()?.stripTrailingZeros() ?: return null
        if (d.scale() > exponent(currency)) return null
        val minor = d.movePointRight(exponent(currency))
        return if (minor <= MAX_MINOR) minor.toLong() else null
    }
    fun format(minor: Long, currency: String): String = BigDecimal(minor).movePointLeft(exponent(currency)).toPlainString() + " " + currency
}
