package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.agent.db.TokenRegistryEntity
import com.wyltek.wallet.core.native.CapInfo
import com.wyltek.wallet.core.native.TokenSpec
import com.wyltek.wallet.core.native.mintToken
import com.wyltek.wallet.core.native.tokenCaps
import com.wyltek.wallet.core.native.tokenIdOf

data class MintedToken(val token: String, val tokenId: String)

/** Mints biscuit tokens via the Rust core and records them in the encrypted registry. */
class AgentTokenService(
    private val keyStore: AgentKeyStore,
    db: AgentDatabase
) {
    private val dao = db.agentDao()

    suspend fun mint(spec: TokenSpec): MintedToken {
        val secret = keyStore.rootSecretHex()
        val pub = keyStore.rootPublicHex()
        val token = mintToken(spec, secret)
        val tokenId = tokenIdOf(token, pub)
        dao.insertToken(
            TokenRegistryEntity(
                tokenId = tokenId,
                token = token,
                account = spec.account,
                revoked = false,
                createdAt = epochSeconds()
            )
        )
        return MintedToken(token, tokenId)
    }

    suspend fun list(): List<TokenRegistryEntity> = dao.allTokens()

    suspend fun revoke(tokenId: String) = dao.revoke(tokenId)

    suspend fun isRevoked(tokenId: String): Boolean = dao.isRevoked(tokenId) ?: false

    fun caps(token: String): List<CapInfo> = tokenCaps(token, keyStore.rootPublicHex())

    private fun epochSeconds(): Long = System.currentTimeMillis() / 1000
}
