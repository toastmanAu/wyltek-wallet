package com.wyltek.wallet.agent.provisioning

/**
 * Compact provisioning bundle for the BlackBox POS, or null if the device is not
 * yet paired to the relay (deviceId null/blank).
 *
 * Format (compact, no spaces — the POS matches the literal substrings
 * `"device_id":"` and `"token":"` with strstr, NOT a JSON parser):
 *   {"device_id":"<uuid>","token":"<biscuit base64>"}
 *
 * Built as a manual string, not org.json.JSONObject: org.json is stubbed in JVM
 * unit tests. Both values are quote-free by construction (UUID + URL-safe base64),
 * so no escaping is required.
 */
fun buildProvisioningBundle(deviceId: String?, token: String): String? {
    val id = deviceId?.ifBlank { null } ?: return null
    return """{"device_id":"$id","token":"$token"}"""
}
