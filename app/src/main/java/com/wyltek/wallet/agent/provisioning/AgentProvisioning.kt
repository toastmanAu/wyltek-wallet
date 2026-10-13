package com.wyltek.wallet.agent.provisioning

/** Stable mDNS instance name for this device: "blackbox-" + first 8 alphanumerics of the id. */
fun serviceNameFor(deviceId: String): String =
    "blackbox-" + deviceId.filter { it.isLetterOrDigit() }.take(8).lowercase()

/**
 * The prepaid fields for one token's QR (I5): K_debit is a secret that lets a terminal write card records,
 * so it travels only with a token that was minted with Scope.PREPAID_SETTLE. Otherwise both are null and
 * [buildProvisioningBundle] emits the three-field bundle.
 */
fun prepaidBundleFields(mintedWithPrepaid: Boolean, kDebitHex: String?, merchantIdHex: String?): Pair<String?, String?> =
    if (mintedWithPrepaid) kDebitHex to merchantIdHex else null to null

/**
 * Compact provisioning bundle for the BlackBox POS, or null if the device is not
 * yet paired to the relay (deviceId null/blank).
 *
 * Format (compact, no spaces — the POS matches the literal substrings
 * `"device_id":"`, `"token":"`, `"service_name":"`, `"k_debit":"` and `"merchant_id":"`
 * with strstr, NOT a JSON parser):
 *   {"device_id":"<uuid>","token":"<biscuit base64>","service_name":"<mDNS name>"}
 *   {"device_id":"<uuid>","token":"<biscuit base64>","service_name":"<mDNS name>","k_debit":"<32 hex>","merchant_id":"<16 hex>"}
 *
 * The prepaid fields k_debit and merchant_id are appended only when BOTH are non-null;
 * otherwise the output is byte-identical to the three-field bundle.
 *
 * Built as a manual string, not org.json.JSONObject: org.json is stubbed in JVM
 * unit tests. All values are quote-free by construction (UUID + URL-safe base64 + mDNS name),
 * so no escaping is required.
 */
fun buildProvisioningBundle(
    deviceId: String?,
    token: String,
    kDebitHex: String? = null,
    merchantIdHex: String? = null,
): String? {
    val id = deviceId?.ifBlank { null } ?: return null
    val svc = serviceNameFor(id)
    val prepaid = if (kDebitHex != null && merchantIdHex != null) {
        ""","k_debit":"$kDebitHex","merchant_id":"$merchantIdHex""""
    } else {
        ""
    }
    return """{"device_id":"$id","token":"$token","service_name":"$svc"$prepaid}"""
}
