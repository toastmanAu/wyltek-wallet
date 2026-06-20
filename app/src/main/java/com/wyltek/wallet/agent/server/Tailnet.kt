package com.wyltek.wallet.agent.server

import java.net.Inet4Address
import java.net.NetworkInterface

object Tailnet {
    /**
     * Returns the device's Tailscale IPv4 (100.64.0.0/10 range) if present, else null.
     * The Tailscale CGNAT range covers 100.64.0.0 – 100.127.255.255.
     */
    fun bindAddress(): String? {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address) {
                    val b = addr.address
                    val first = b[0].toInt() and 0xFF
                    val second = b[1].toInt() and 0xFF
                    // 100.64.0.0/10 == 100.64.x.x .. 100.127.x.x
                    if (first == 100 && second in 64..127) return addr.hostAddress
                }
            }
        }
        return null
    }
}
