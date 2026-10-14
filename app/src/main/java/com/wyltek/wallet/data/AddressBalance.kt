package com.wyltek.wallet.data

/** Balance of one of an account's addresses (e.g. a Hybrid account's Classic and PQ locks). */
data class AddressBalance(val label: String, val shannons: ULong) {
    companion object {
        fun formatCkb(shannons: ULong): String {
            val whole = shannons / 100_000_000u
            val fraction = (shannons % 100_000_000u).toString().padStart(8, '0').trimEnd('0')
            return if (fraction.isEmpty()) "$whole CKB" else "$whole.$fraction CKB"
        }

        fun total(list: List<AddressBalance>): ULong = list.fold(0uL) { acc, b -> acc + b.shannons }

        fun breakdown(list: List<AddressBalance>): String =
            list.joinToString(" · ") { "${it.label} ${formatCkb(it.shannons)}" }
    }
}
