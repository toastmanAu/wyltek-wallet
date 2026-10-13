package com.wyltek.wallet.prepaid

/** CKB text ⇄ milli-CKB units. Strict: digits, at most three decimals, positive, no separators or exponents. */
object PrepaidAmount {
    private val pattern = Regex("""^(\d+)(?:\.(\d{1,3}))?$""")
    fun parseCkbToUnits(text: String): Int? {
        val m = pattern.matchEntire(text.trim()) ?: return null
        val whole = m.groupValues[1].toLongOrNull() ?: return null
        val frac = m.groupValues[2].padEnd(3, '0').toLong()
        if (whole > Int.MAX_VALUE) return null
        val units = whole * 1000 + frac
        return if (units <= 0 || units > Int.MAX_VALUE) null else units.toInt()
    }
    fun formatUnits(units: Long): String {
        val sign = if (units < 0) "-" else ""
        val a = kotlin.math.abs(units)
        return "%s%d.%03d CKB".format(sign, a / 1000, a % 1000)
    }
}
