package com.wyltek.wallet.core.messaging

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

class ContactBook(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("contact_book", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun addContact(contact: ContactProfile) {
        val contacts = getAllContacts().toMutableList()
        val existingIndex = contacts.indexOfFirst { it.address == contact.address }
        if (existingIndex >= 0) {
            contacts[existingIndex] = contact
        } else {
            contacts.add(contact)
        }
        saveContacts(contacts)
    }

    fun removeContact(address: String) {
        val contacts = getAllContacts().toMutableList()
        contacts.removeAll { it.address == address }
        saveContacts(contacts)
    }

    fun getContact(address: String): ContactProfile? {
        return getAllContacts().find { it.address == address }
    }

    fun getAllContacts(): List<ContactProfile> {
        val jsonStr = prefs.getString("contacts", null) ?: return emptyList()
        return try {
            val jsonArray = json.parseToJsonElement(jsonStr).jsonArray
            jsonArray.map { element ->
                val obj = element.jsonObject
                ContactProfile(
                    address = obj["address"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content,
                    publicKey = obj["publicKey"]?.jsonPrimitive?.content?.let { hexToBytes(it) },
                    discovered = obj["discovered"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun searchContacts(query: String): List<ContactProfile> {
        val lowercaseQuery = query.lowercase()
        return getAllContacts().filter { contact ->
            contact.name?.lowercase()?.contains(lowercaseQuery) == true ||
            contact.address.lowercase().contains(lowercaseQuery)
        }
    }

    private fun saveContacts(contacts: List<ContactProfile>) {
        val jsonArray = contacts.map { contact ->
            buildJsonObject {
                put("address", contact.address)
                contact.name?.let { put("name", it) }
                contact.publicKey?.let { put("publicKey", bytesToHex(it)) }
                put("discovered", contact.discovered)
            }
        }
        val jsonString = Json.encodeToString(jsonArray)
        prefs.edit().putString("contacts", jsonString).apply()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x")
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toByte(16)
        }
        return bytes
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
