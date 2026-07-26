package com.wyltek.wallet.agent

import java.security.MessageDigest

/** Opaque, idempotent intent id for the /relay facade: sha256(token ‖ 0x00 ‖ nonce), hex.
 *  The 0x00 separator prevents (token,nonce) boundary-shift collisions. */
fun relayIntentId(token: String, nonce: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(token.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(nonce.toByteArray(Charsets.UTF_8))
    return md.digest().joinToString("") { "%02x".format(it) }
}
