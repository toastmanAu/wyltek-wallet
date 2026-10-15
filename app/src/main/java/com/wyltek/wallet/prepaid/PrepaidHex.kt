package com.wyltek.wallet.prepaid

/**
 * The one hash shape the prepaid code stores and compares: `0x` + lower-case hex. rust-core's
 * `build_transaction` returns its hash WITHOUT a prefix while the node and the indexer use `0x…`,
 * so every hash crossing a port boundary goes through here.
 */
fun ensure0x(hex: String): String =
    if (hex.startsWith("0x") || hex.startsWith("0X")) "0x" + hex.substring(2).lowercase() else "0x" + hex.lowercase()

/** `"txhash:index"` with the hash normalised by [ensure0x]; the index is left as written. */
fun normalizeOutpoint(outpoint: String): String {
    val i = outpoint.lastIndexOf(':')
    return if (i < 0) ensure0x(outpoint) else ensure0x(outpoint.substring(0, i)) + outpoint.substring(i)
}

/** Even-length hex (optional 0x) → bytes. Throws IllegalArgumentException on anything else. */
fun hexBytes(hex: String): ByteArray {
    val h = hex.removePrefix("0x").removePrefix("0X")
    require(h.length % 2 == 0 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
fun bytesHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
