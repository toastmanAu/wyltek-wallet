package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.KIND_STAFF_LOCKOUT
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
import com.wyltek.wallet.prepaid.db.StaffKeyEntity

/** What the Staff keys list shows for one key. Pure, so the rules are JVM-testable. */
sealed class StaffKeyStatus {
    data object Revoked : StaffKeyStatus()
    /** No PIN set: the key is not served to terminals at all. */
    data object NoPin : StaffKeyStatus()
    /** A terminal locked the key at its current pin_ver; a new PIN (pin_ver + 1) unlocks it. */
    data class Locked(val terminalId: Int, val atMs: Long) : StaffKeyStatus()
    data class Served(val pinVer: Int) : StaffKeyStatus()
}

/** Newest staff_lockout event per key_id (events arrive newest first from eventsFlow). Lockout rows carry the
 *  key id in `reserveId`, the terminal id in `balanceAfter` and the locked pin_ver in `counterAfter`. */
fun latestLockouts(events: List<PrepaidEventEntity>): Map<String, PrepaidEventEntity> {
    val out = LinkedHashMap<String, PrepaidEventEntity>()
    for (e in events) {
        val keyId = e.reserveId ?: continue
        if (e.kind == KIND_STAFF_LOCKOUT && keyId !in out) out[keyId] = e
    }
    return out
}

fun staffKeyStatus(k: StaffKeyEntity, latestLockout: PrepaidEventEntity?): StaffKeyStatus = when {
    k.revokedAt != null -> StaffKeyStatus.Revoked
    k.pinCheckHex == null -> StaffKeyStatus.NoPin
    latestLockout != null && latestLockout.counterAfter == k.pinVer.toLong() -> StaffKeyStatus.Locked(latestLockout.balanceAfter, latestLockout.atMs)
    else -> StaffKeyStatus.Served(k.pinVer)
}
