package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.native.agentRootKeypair
import com.wyltek.wallet.core.security.StrongBoxManager

/**
 * Provisions and stores the biscuit root Ed25519 keypair — independent of the wallet
 * seed (compromise of one is not compromise of the other). The secret is sealed with
 * StrongBox when available (hardware-backed AES-256-GCM via AndroidKeyStore), else
 * stored directly via the EncryptedSharedPreferences AES-GCM in AgentSecureStore.
 * The public half is always stored in the clear (it's public).
 *
 * All operations are idempotent: the first call provisions, subsequent calls load.
 */
class AgentKeyStore(
    private val secure: AgentSecureStore,
    private val strongBox: StrongBoxManager
) {
    /** Returns the hex-encoded Ed25519 public key, provisioning on first use. */
    fun rootPublicHex(): String {
        provisionIfNeeded()
        return String(secure.loadBlob(KEY_PUB)!!, Charsets.UTF_8)
    }

    /**
     * Returns the hex-encoded Ed25519 secret key, provisioning on first use.
     * If StrongBox is available, the stored bytes are unwrapped first.
     */
    fun rootSecretHex(): String {
        provisionIfNeeded()
        val stored = secure.loadBlob(KEY_SEC_SEALED)!!
        val raw = if (secure.loadBlob(KEY_SEC_STRONGBOX_USED) != null) {
            strongBox.unwrapPrivateKey(stored) ?: stored
        } else {
            stored
        }
        return String(raw, Charsets.UTF_8)
    }

    private fun provisionIfNeeded() {
        if (secure.loadBlob(KEY_PUB) != null && secure.loadBlob(KEY_SEC_SEALED) != null) return

        val kp = agentRootKeypair()

        secure.storeBlob(KEY_PUB, kp.publicHex.toByteArray(Charsets.UTF_8))

        val secretBytes = kp.secretHex.toByteArray(Charsets.UTF_8)
        val sbAvailable = strongBox.isStrongBoxAvailable()
        if (sbAvailable) {
            val wrapped = strongBox.wrapPrivateKey(secretBytes)
            if (wrapped != null) {
                secure.storeBlob(KEY_SEC_SEALED, wrapped)
                secure.storeBlob(KEY_SEC_STRONGBOX_USED, byteArrayOf(1))
                return
            }
        }
        // StrongBox unavailable or wrap failed — store via EncryptedSharedPreferences
        secure.storeBlob(KEY_SEC_SEALED, secretBytes)
    }

    companion object {
        private const val KEY_PUB = "biscuit_root_pub_hex"
        private const val KEY_SEC_SEALED = "biscuit_root_sec_sealed"
        private const val KEY_SEC_STRONGBOX_USED = "biscuit_root_sec_strongbox"
    }
}
