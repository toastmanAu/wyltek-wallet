package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StaffKeyServiceTest {
    private val store = InMemoryPrepaidStore(); private val vstore = InMemoryVoucherStore(); private var now = 1L; private var ids = 0
    private var genuine = true
    private val svc = StaffKeyService(store, vstore, FakeProtocol(), { now++ }, { "s${++ids}" }, verifySig = { _, _ -> genuine })

    @Test fun enrol_stores_uid_and_signature_and_audits() = runTest {
        val tag = FakeTag("04958caa5c5e80")
        assertEquals(ActionOutcome.Done, svc.enrol(tag, "Phill's ring"))
        val k = svc.active().single()
        assertEquals("04958caa5c5e80", k.uid); assertEquals("5a".repeat(32), k.sigHex); assertEquals("Phill's ring", k.label); assertEquals(16, k.keyId.length)
        assertEquals(KIND_STAFF_ENROL, store.events.single().kind)
    }
    @Test fun a_tag_that_fails_nxp_verification_is_refused_and_nothing_is_stored() = runTest {
        genuine = false
        assertEquals(ActionOutcome.Failed(StaffKeyService.NOT_GENUINE), svc.enrol(FakeTag("04958caa5c5e80"), "x"))
        assertTrue(svc.active().isEmpty()); assertTrue(store.events.isEmpty())
    }
    @Test fun enrolling_twice_is_refused_and_a_blank_label_is_refused() = runTest {
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "a"))
        assertTrue(svc.enrol(FakeTag("04958caa5c5e80"), "b") is ActionOutcome.Failed)
        assertTrue(svc.enrol(FakeTag("04ebbb02cd2a81"), "  ") is ActionOutcome.Failed)
    }
    @Test fun a_lost_tag_needs_the_tag_again() = runTest {
        val t = FakeTag("04958caa5c5e80").also { it.present = false }
        assertTrue(svc.enrol(t, "a") is ActionOutcome.NeedsTag)
    }
    @Test fun revoke_drops_it_from_active_and_audits_and_a_revoked_key_can_be_enrolled_again() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a"); val id = svc.active().single().keyId
        assertEquals(ActionOutcome.Done, svc.revoke(id))
        assertTrue(svc.active().isEmpty()); assertEquals(KIND_STAFF_REVOKE, store.events.last().kind)
        assertTrue(svc.revoke(id) is ActionOutcome.Failed)
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "again"))
        assertEquals(1, svc.active().size)
    }

    @Test fun the_sixteen_key_cap_is_enforced_and_a_revoke_frees_a_slot() = runTest {
        fun uid(i: Int) = "04" + "%012x".format(i)
        for (i in 1..16) assertEquals(ActionOutcome.Done, svc.enrol(FakeTag(uid(i)), "k$i"))
        val events = store.events.size
        assertTrue(svc.enrol(FakeTag(uid(17)), "k17") is ActionOutcome.Failed)
        assertEquals(16, svc.active().size); assertEquals(events, store.events.size)
        assertTrue(vstore.keys.values.none { it.uid == uid(17) })
        svc.revoke(svc.active().first().keyId)
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag(uid(17)), "k17"))
        assertEquals(16, svc.active().size)
    }
    @Test fun verify_gets_the_raw_uid_bytes_in_tag_order() = runTest {
        var seen: ByteArray? = null
        val s = StaffKeyService(store, vstore, FakeProtocol(), { now++ }, { "s${++ids}" }, verifySig = { u, _ -> seen = u; true })
        s.enrol(FakeTag("04958caa5c5e80"), "a")
        assertArrayEquals(byteArrayOf(0x04, 0x95.toByte(), 0x8c.toByte(), 0xaa.toByte(), 0x5c, 0x5e, 0x80.toByte()), seen)
    }
    @Test fun re_enrol_reactivates_the_same_key_id() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a"); val id = svc.active().single().keyId
        svc.revoke(id); svc.enrol(FakeTag("04958caa5c5e80"), "again")
        val k = svc.active().single(); assertEquals(id, k.keyId); assertNull(k.revokedAt); assertEquals("again", k.label)
    }
    @Test fun uppercase_tag_uid_is_stored_lowercase_with_the_same_key_id() = runTest {
        svc.enrol(FakeTag("04958CAA5C5E80"), "a")
        assertEquals("04958caa5c5e80", svc.active().single().uid)
    }
}
