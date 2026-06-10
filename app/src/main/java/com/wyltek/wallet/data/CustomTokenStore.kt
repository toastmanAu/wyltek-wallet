package com.wyltek.wallet.data

import android.content.Context
import android.content.SharedPreferences
import com.wyltek.wallet.core.assets.CustomTokenDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CustomTokenStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("custom_tokens", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getAllTokens(): List<CustomTokenDefinition> {
        val tokensJson = prefs.getString("tokens", "[]") ?: "[]"
        return try {
            json.decodeFromString(tokensJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addToken(token: CustomTokenDefinition): Boolean {
        val current = getAllTokens().toMutableList()
        // Prevent duplicates by args
        if (current.any { it.args.equals(token.args, ignoreCase = true) }) {
            return false
        }
        current.add(token)
        prefs.edit().putString("tokens", json.encodeToString(current)).apply()
        return true
    }

    fun removeToken(args: String) {
        val current = getAllTokens().filterNot { it.args.equals(args, ignoreCase = true) }
        prefs.edit().putString("tokens", json.encodeToString(current)).apply()
    }
}
