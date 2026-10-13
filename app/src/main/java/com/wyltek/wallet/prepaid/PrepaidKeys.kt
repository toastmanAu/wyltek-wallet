package com.wyltek.wallet.prepaid

import java.security.SecureRandom

/** K_debit: 16 random bytes, generated once, kept in the agent secure store (D3 spec §2.10, §3.3).
 *  Storage is injected so the class is host-testable; on device it is AgentSecureStore::loadBlob/storeBlob. */
class PrepaidKeys(
    private val load: (String) -> ByteArray?,
    private val store: (String, ByteArray) -> Unit,
    private val random: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
) {
    fun hasKDebit(): Boolean = load(KEY_K_DEBIT) != null

    fun kDebit(): ByteArray {
        load(KEY_K_DEBIT)?.let { check(it.size == 16) { "stored k_debit is ${it.size} bytes" }; return it }
        val k = random()
        check(k.size == 16) { "generated k_debit is ${k.size} bytes" }
        store(KEY_K_DEBIT, k)
        return k
    }

    /** Makes sure K_debit exists; true when this call generated it (the caller must announce it, I6). */
    fun ensureKDebit(): Boolean { val had = hasKDebit(); kDebit(); return !had }

    fun kDebitHex(): String = kDebit().joinToString("") { "%02x".format(it) }

    companion object { const val KEY_K_DEBIT = "prepaid.k_debit" }
}
