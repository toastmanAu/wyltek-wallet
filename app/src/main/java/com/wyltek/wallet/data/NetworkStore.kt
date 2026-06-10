package com.wyltek.wallet.data

import android.content.Context
import android.content.SharedPreferences
import com.wyltek.wallet.core.model.NetworkType

class NetworkStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("network_prefs", Context.MODE_PRIVATE)

    fun getCurrentNetwork(): NetworkType {
        val name = prefs.getString("current_network", NetworkType.TESTNET.name) ?: NetworkType.TESTNET.name
        return try {
            NetworkType.valueOf(name)
        } catch (e: Exception) {
            NetworkType.TESTNET
        }
    }

    fun setCurrentNetwork(network: NetworkType) {
        prefs.edit().putString("current_network", network.name).apply()
    }
}
