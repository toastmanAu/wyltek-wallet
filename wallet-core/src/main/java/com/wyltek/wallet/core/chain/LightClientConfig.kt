package com.wyltek.wallet.core.chain

/** Generates the `config.toml` the embedded ckb-light-client reads at startup. */
object LightClientConfig {
    fun generate(
        storePath: String,
        networkPath: String,
        rpcListen: String = "127.0.0.1:9000",
        chain: String = "testnet",
        bootnodes: List<String>
    ): String {
        val boots = bootnodes.joinToString(",\n") { "  \"$it\"" }
        return """
            chain = "$chain"

            [store]
            path = "$storePath"

            [network]
            path = "$networkPath"
            listen_addresses = ["/ip4/0.0.0.0/tcp/8118"]
            bootnodes = [
            $boots
            ]
            max_peers = 125
            max_outbound_peers = 8
            ping_interval_secs = 120
            ping_timeout_secs = 1200
            connect_outbound_interval_secs = 15
            upnp = false
            discovery_local_address = false
            bootnode_mode = false

            [rpc]
            listen_address = "$rpcListen"
        """.trimIndent()
    }
}
