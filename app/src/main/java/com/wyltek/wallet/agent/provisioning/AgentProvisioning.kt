package com.wyltek.wallet.agent.provisioning

/** Stable mDNS instance name for this device: "blackbox-" + first 8 alphanumerics of the id. */
fun serviceNameFor(deviceId: String): String =
    "blackbox-" + deviceId.filter { it.isLetterOrDigit() }.take(8).lowercase()

/**
 * Compact provisioning bundle for the BlackBox POS, or null if the device is not
 * yet paired to the relay (deviceId null/blank).
 *
 * Format (compact, no spaces — the POS matches the literal substrings
 * `"device_id":"`, `"token":"` and `"service_name":"` with strstr, NOT a JSON parser):
 *   {"device_id":"<uuid>","token":"<biscuit base64>","service_name":"<mDNS name>"}
 *
 * Built as a manual string, not org.json.JSONObject: org.json is stubbed in JVM
 * unit tests. All values are quote-free by construction (UUID + URL-safe base64 + mDNS name),
 * so no escaping is required.
 */
fun buildProvisioningBundle(deviceId: String?, token: String): String? {
    val id = deviceId?.ifBlank { null } ?: return null
    val svc = serviceNameFor(id)
    return """{"device_id":"$id","token":"$token","service_name":"$svc"}"""
}
