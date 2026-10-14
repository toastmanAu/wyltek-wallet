package com.wyltek.wallet.ui.screens

/**
 * Pulls the first CKB address out of whatever text a scanned QR carries: a bare address, a
 * `ckb:`-style URI, or a link with the address in a query string (JoyID's receive QR does this).
 * Bech32m is case-insensitive, so the match is lowercased. Null when the text has no address.
 */
object QrAddress {
    // Full-length CKB2021 addresses are 90+ characters; the floor keeps prefixes and short
    // deprecated forms from matching while staying well under any real address.
    private val pattern = Regex("""ck[bt]1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{40,}""", RegexOption.IGNORE_CASE)

    fun extract(text: String): String? = pattern.find(text)?.value?.lowercase()
}
