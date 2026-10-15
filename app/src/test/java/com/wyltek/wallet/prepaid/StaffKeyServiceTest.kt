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
}
