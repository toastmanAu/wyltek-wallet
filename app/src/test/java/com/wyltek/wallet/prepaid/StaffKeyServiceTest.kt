package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StaffKeyServiceTest {
    private val store = InMemoryPrepaidStore(); private val vstore = InMemoryVoucherStore(); private var now = 1L; private var ids = 0
    private var genuine = true
    private val svc = StaffKeyService(store, vstore, FakeProtocol(), { "10".repeat(16) }, { now++ }, { "s${++ids}" }, verifySig = { _, _ -> genuine })

    @Test fun enrol_stores_uid_and_signature_and_audits() = runTest {
        val tag = FakeTag("04958caa5c5e80")
        assertEquals(ActionOutcome.Done, svc.enrol(tag, "Phill's ring", "1234"))
        val k = svc.active().single()
        assertEquals("04958caa5c5e80", k.uid); assertEquals("5a".repeat(32), k.sigHex); assertEquals("Phill's ring", k.label); assertEquals(16, k.keyId.length)
        assertEquals(KIND_STAFF_ENROL, store.events.first().kind)
    }
    @Test fun a_tag_that_fails_nxp_verification_is_refused_and_nothing_is_stored() = runTest {
        genuine = false
        assertEquals(ActionOutcome.Failed(StaffKeyService.NOT_GENUINE), svc.enrol(FakeTag("04958caa5c5e80"), "x", "1234"))
        assertTrue(svc.active().isEmpty()); assertTrue(store.events.isEmpty())
    }
    @Test fun enrolling_twice_is_refused_and_a_blank_label_is_refused() = runTest {
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"))
        assertTrue(svc.enrol(FakeTag("04958caa5c5e80"), "b", "1234") is ActionOutcome.Failed)
        assertTrue(svc.enrol(FakeTag("04ebbb02cd2a81"), "  ", "1234") is ActionOutcome.Failed)
    }
    @Test fun a_lost_tag_needs_the_tag_again() = runTest {
        val t = FakeTag("04958caa5c5e80").also { it.present = false }
        assertTrue(svc.enrol(t, "a", "1234") is ActionOutcome.NeedsTag)
    }
    @Test fun revoke_drops_it_from_active_and_audits_and_a_revoked_key_can_be_enrolled_again() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val id = svc.active().single().keyId
        assertEquals(ActionOutcome.Done, svc.revoke(id))
        assertTrue(svc.active().isEmpty()); assertEquals(KIND_STAFF_REVOKE, store.events.last().kind)
        assertTrue(svc.revoke(id) is ActionOutcome.Failed)
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "again", "1234"))
        assertEquals(1, svc.active().size)
    }

    @Test fun the_sixteen_key_cap_is_enforced_and_a_revoke_frees_a_slot() = runTest {
        fun uid(i: Int) = "04" + "%012x".format(i)
        for (i in 1..16) assertEquals(ActionOutcome.Done, svc.enrol(FakeTag(uid(i)), "k$i", "1234"))
        val events = store.events.size
        assertTrue(svc.enrol(FakeTag(uid(17)), "k17", "1234") is ActionOutcome.Failed)
        assertEquals(16, svc.active().size); assertEquals(events, store.events.size)
        assertTrue(vstore.keys.values.none { it.uid == uid(17) })
        svc.revoke(svc.active().first().keyId)
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag(uid(17)), "k17", "1234"))
        assertEquals(16, svc.active().size)
    }
    @Test fun verify_gets_the_raw_uid_bytes_in_tag_order() = runTest {
        var seen: ByteArray? = null
        val s = StaffKeyService(store, vstore, FakeProtocol(), { "10".repeat(16) }, { now++ }, { "s${++ids}" }, verifySig = { u, _ -> seen = u; true })
        s.enrol(FakeTag("04958caa5c5e80"), "a", "1234")
        assertArrayEquals(byteArrayOf(0x04, 0x95.toByte(), 0x8c.toByte(), 0xaa.toByte(), 0x5c, 0x5e, 0x80.toByte()), seen)
    }
    @Test fun re_enrol_reactivates_the_same_key_id() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val id = svc.active().single().keyId
        svc.revoke(id); svc.enrol(FakeTag("04958caa5c5e80"), "again", "1234")
        val k = svc.active().single(); assertEquals(id, k.keyId); assertNull(k.revokedAt); assertEquals("again", k.label)
    }
    @Test fun uppercase_tag_uid_is_stored_lowercase_with_the_same_key_id() = runTest {
        svc.enrol(FakeTag("04958CAA5C5E80"), "a", "1234")
        assertEquals("04958caa5c5e80", svc.active().single().uid)
    }

    private val fake = FakeProtocol()   // same digest as the service's FakeProtocol: both are pure functions of the inputs
    @Test fun enrol_sets_pin_ver_1_and_a_pin_check_and_audits_pin_set_after_enrol() = runTest {
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "ring", "0042"))
        val k = svc.active().single()
        assertEquals(1, k.pinVer); assertEquals(fake.staffPinCheck("10".repeat(16), k.keyId, 1, "0042"), k.pinCheckHex); assertNotNull(k.pinSetAt)
        assertEquals(listOf(KIND_STAFF_ENROL, KIND_STAFF_PIN_SET), store.events.map { it.kind })
        assertEquals("pin_ver 1", store.events.last().detail); assertEquals(k.keyId, store.events.last().reserveId)
    }
    @Test fun a_bad_pin_is_refused_before_the_tag_is_read() = runTest {
        val gone = FakeTag("04958caa5c5e80").also { it.present = false }   // a read would answer NeedsTag
        for (bad in listOf("123", "123456789", "12a4", "12 4", "\u0661\u0662\u0663\u0664", "\uff11\uff12\uff13\uff14", "")) assertEquals(bad, ActionOutcome.Failed(StaffKeyService.PIN_RULE), svc.enrol(gone, "ring", bad))
        assertTrue(svc.active().isEmpty()); assertTrue(store.events.isEmpty())
        for (good in listOf("1234", "12345678", "0000")) assertNull(StaffKeyService.pinError(good))
    }
    @Test fun leading_zeros_reach_the_derivation_unchanged() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a", "0042"); val k = svc.active().single()
        assertEquals(fake.staffPinCheck("10".repeat(16), k.keyId, 1, "0042"), k.pinCheckHex)
        assertNotEquals(fake.staffPinCheck("10".repeat(16), k.keyId, 1, "1042"), k.pinCheckHex)
    }
    @Test fun set_pin_bumps_pin_ver_and_changes_pin_check_even_for_the_same_digits() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val before = svc.active().single()
        assertEquals(ActionOutcome.Done, svc.setPin(before.keyId, "1234")); val after = svc.active().single()
        assertEquals(2, after.pinVer); assertNotEquals(before.pinCheckHex, after.pinCheckHex); assertTrue(after.pinSetAt!! > before.pinSetAt!!)
        assertEquals("pin_ver 2", store.events.last().detail); assertEquals(KIND_STAFF_PIN_SET, store.events.last().kind)
        assertEquals(ActionOutcome.Failed(StaffKeyService.PIN_RULE), svc.setPin(before.keyId, "12"))
    }
    @Test fun set_pin_on_a_revoked_or_unknown_key_is_refused() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val id = svc.active().single().keyId; svc.revoke(id)
        assertTrue(svc.setPin(id, "1234") is ActionOutcome.Failed); assertTrue(svc.setPin("00".repeat(8), "1234") is ActionOutcome.Failed)
    }
    @Test fun re_enrol_after_revoke_continues_pin_ver() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val id = svc.active().single().keyId
        svc.setPin(id, "1234"); svc.revoke(id)
        assertEquals("revoke keeps the PIN columns", 2, vstore.keys[id]!!.pinVer)
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "again", "9999"))
        assertEquals(3, svc.active().single().pinVer)
    }
    @Test fun pin_ver_at_65535_refuses_set_pin_and_re_enrol() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val k = svc.active().single()
        vstore.updateStaffKey(k.copy(pinVer = 65535))
        assertEquals(ActionOutcome.Failed(StaffKeyService.PIN_VER_EXHAUSTED), svc.setPin(k.keyId, "1234"))
        svc.revoke(k.keyId)
        assertEquals(ActionOutcome.Failed(StaffKeyService.PIN_VER_EXHAUSTED), svc.enrol(FakeTag("04958caa5c5e80"), "b", "1234"))
        assertEquals(65535, vstore.keys[k.keyId]!!.pinVer)
    }
    @Test fun no_audit_row_ever_contains_the_pin() = runTest {
        val pin = "97315084"
        svc.enrol(FakeTag("04958caa5c5e80"), "ring", pin); svc.setPin(svc.active().single().keyId, pin); svc.revoke(svc.active().single().keyId)
        assertTrue(store.events.size >= 4)
        for (e in store.events) for (s in listOf(e.detail, e.uid, e.reserveId ?: "", e.kind, e.txHash ?: "")) assertFalse("$e", s.contains(pin))
        assertFalse(vstore.keys.values.any { it.label.contains(pin) || it.sigHex.contains(pin) })
    }
}
