package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.KIND_STAFF_LOCKOUT
import com.wyltek.wallet.prepaid.db.KIND_STAFF_PIN_SET
import com.wyltek.wallet.prepaid.db.PHASE_DONE
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
import com.wyltek.wallet.prepaid.db.StaffKeyEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffKeyStatusTest {
    private fun key(pin: Boolean, ver: Int = 1, revoked: Long? = null) = StaffKeyEntity("77".repeat(8), "04958caa5c5e80", "5a".repeat(32), "ring", 1, revoked,
        pinVer = if (pin) ver else 0, pinCheckHex = if (pin) "ab".repeat(16) else null, pinSetAt = if (pin) 1 else null)
    private fun lockout(id: Long, keyId: String, pinVer: Long, terminal: Int, at: Long) = PrepaidEventEntity(id = id, atMs = at, actionId = "a$id", kind = KIND_STAFF_LOCKOUT,
        phase = PHASE_DONE, reserveId = keyId, uid = "04010203040506", amountUnits = 3, balanceBefore = 0, balanceAfter = terminal, counterBefore = 0, counterAfter = pinVer, txHash = null, detail = "")
    @Test fun no_pin_wins_over_everything_but_revoked() { assertEquals(StaffKeyStatus.NoPin, staffKeyStatus(key(false), listOf(lockout(1, "77".repeat(8), 0, 9, 5)))); assertEquals(StaffKeyStatus.Revoked, staffKeyStatus(key(false, revoked = 3), emptyList())) }
    @Test fun a_lockout_at_the_current_pin_ver_shows_locked_with_terminal_and_time() { assertEquals(StaffKeyStatus.Locked(9, 5), staffKeyStatus(key(true, ver = 2), listOf(lockout(1, "77".repeat(8), 2, 9, 5)))) }
    @Test fun a_lockout_at_an_older_pin_ver_is_cleared_by_set_pin() { assertEquals(StaffKeyStatus.Served(3), staffKeyStatus(key(true, ver = 3), listOf(lockout(1, "77".repeat(8), 2, 9, 5)))) }
    @Test fun a_lockout_at_a_newer_pin_ver_than_the_key_is_not_locked() { assertEquals(StaffKeyStatus.Served(2), staffKeyStatus(key(true, ver = 2), listOf(lockout(1, "77".repeat(8), 3, 9, 5)))) }
    @Test fun lockouts_by_key_groups_newest_first_per_key_and_ignores_other_kinds() {
        val a = lockout(3, "aa".repeat(8), 1, 1, 30); val older = lockout(2, "aa".repeat(8), 1, 2, 20); val b = lockout(1, "bb".repeat(8), 1, 3, 10)
        val other = older.copy(id = 4, kind = KIND_STAFF_PIN_SET)
        assertEquals(mapOf("aa".repeat(8) to listOf(a, older), "bb".repeat(8) to listOf(b)), lockoutsByKey(listOf(other, a, older, b)))
    }
    /** Security review F1: terminal A locks at the current pin_ver 3; terminal B, never synced past pin_ver 2, locks at 2 and
     *  settles later. The newer row is stale (pin_ver 2) and must not hide A's live lockout. */
    @Test fun a_stale_lockout_settling_after_a_live_one_does_not_hide_it() {
        val rows = listOf(lockout(2, "77".repeat(8), 2, 5, 20), lockout(1, "77".repeat(8), 3, 9, 10))
        assertEquals(StaffKeyStatus.Locked(9, 10), staffKeyStatus(key(true, ver = 3), rows))
    }
    @Test fun the_newest_lockout_at_the_current_pin_ver_wins() {
        val rows = listOf(lockout(3, "77".repeat(8), 3, 4, 30), lockout(2, "77".repeat(8), 2, 5, 20), lockout(1, "77".repeat(8), 3, 9, 10))
        assertEquals(StaffKeyStatus.Locked(4, 30), staffKeyStatus(key(true, ver = 3), rows))
    }
}
