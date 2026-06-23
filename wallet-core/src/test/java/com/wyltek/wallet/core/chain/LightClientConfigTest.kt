package com.wyltek.wallet.core.chain

import org.junit.Assert.assertTrue
import org.junit.Test

class LightClientConfigTest {
    private val boots = listOf("/ip4/18.217.146.65/tcp/8111/p2p/QmT6DFfm18wtbJz3y4aPNn3ac86N4d4p4xtfQRRPf73frC")

    @Test fun emits_required_sections_and_values() {
        val toml = LightClientConfig.generate(
            storePath = "/data/lc/store",
            networkPath = "/data/lc/network",
            bootnodes = boots
        )
        assertTrue(toml.contains("chain = \"testnet\""))
        assertTrue(toml.contains("[store]"))
        assertTrue(toml.contains("path = \"/data/lc/store\""))
        assertTrue(toml.contains("[network]"))
        assertTrue(toml.contains("path = \"/data/lc/network\""))
        assertTrue(toml.contains("[rpc]"))
        assertTrue(toml.contains("listen_address = \"127.0.0.1:9000\""))
        // UPnP must stay off on mobile (dormant openssl path; CGNAT anyway).
        assertTrue(toml.contains("upnp = false"))
        assertTrue(toml.contains(boots[0]))
    }
}
