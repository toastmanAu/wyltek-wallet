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

/** staff_lockout events per key_id, newest first (events arrive newest first from eventsFlow). Lockout rows carry the
 *  key id in `reserveId`, the terminal id in `balanceAfter` and the locked pin_ver in `counterAfter`. */
fun lockoutsByKey(events: List<PrepaidEventEntity>): Map<String, List<PrepaidEventEntity>> {
    val out = LinkedHashMap<String, MutableList<PrepaidEventEntity>>()
    for (e in events) {
        val keyId = e.reserveId ?: continue
        if (e.kind == KIND_STAFF_LOCKOUT) out.getOrPut(keyId) { mutableListOf() } += e
    }
    return out
}

/** [lockoutsForKey] newest first. Locked uses the newest lockout AT THE KEY'S CURRENT pin_ver: a lockout at another
 *  pin_ver (a terminal that had not synced the latest PIN) settling later must not hide a live one (security review F1). */
fun staffKeyStatus(k: StaffKeyEntity, lockoutsForKey: List<PrepaidEventEntity>): StaffKeyStatus {
    val live = lockoutsForKey.firstOrNull { it.counterAfter == k.pinVer.toLong() }
    return when {
        k.revokedAt != null -> StaffKeyStatus.Revoked
        k.pinCheckHex == null -> StaffKeyStatus.NoPin
        live != null -> StaffKeyStatus.Locked(live.balanceAfter, live.atMs)
        else -> StaffKeyStatus.Served(k.pinVer)
    }
}
