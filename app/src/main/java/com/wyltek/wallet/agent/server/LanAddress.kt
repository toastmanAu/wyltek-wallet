package com.wyltek.wallet.agent.server

import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddress {
    /** Active site-local IPv4 (192.168/, 10., 172.16–31) — the LAN/Wi-Fi address, skipping
     *  loopback, the Tailnet CGNAT range (100.64–127), and non-site-local addresses. Null if none. */
    fun bindAddress(): String? {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                if (addr.isSiteLocalAddress) return addr.hostAddress   // 10/8, 172.16/12, 192.168/16
            }
        }
        return null
    }
}
