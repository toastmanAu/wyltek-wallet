package com.wyltek.wallet.data

import com.wyltek.wallet.core.model.AccountType

/**
 * Post-Quantum and Hybrid accounts derive their ML-DSA-65 key from the BIP39
 * seed, so the phrase's entropy caps the key's security: 12 words give only
 * 128 bits. Those account types require a 24-word (256-bit) phrase; Classic
 * accepts any standard length.
 */
object PqEntropyRule {
    const val PQ_WORD_COUNT = 24
    const val MESSAGE =
        "Post-quantum accounts need a 24-word phrase: 12 words give only 128-bit security"

    fun allows(type: AccountType, wordCount: Int): Boolean = when (type) {
        AccountType.CLASSIC -> true
        AccountType.POST_QUANTUM, AccountType.HYBRID -> wordCount == PQ_WORD_COUNT
    }

    fun wordCount(mnemonic: String): Int {
        val trimmed = mnemonic.trim()
        return if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    }

    /** Null when [mnemonic] is acceptable for [type], else the user-facing error. */
    fun check(type: AccountType, mnemonic: String): String? =
        if (allows(type, wordCount(mnemonic))) null else MESSAGE
}
