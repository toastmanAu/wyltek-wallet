package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.nfc.ReaderModeOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device checkpoint 2026-10-16: navigating Prepaid → Staff keys, the Prepaid screen's late dispose turned reader mode off. */
class ReaderModeOwnerTest {
    @Test fun a_screen_leaving_after_the_next_one_claimed_does_not_turn_the_reader_off() {
        val owner = ReaderModeOwner()
        val prepaid = Any(); val staff = Any()
        owner.claim(prepaid)
        owner.claim(staff)                       // the new screen starts first
        assertFalse(owner.release(prepaid))      // the old screen's dispose must not disable
        assertTrue(owner.holds(staff))
        assertTrue(owner.release(staff))         // the current holder may disable
        assertFalse(owner.holds(staff))
    }

    @Test fun only_the_holder_may_re_enable_after_a_camera_closes() {
        val owner = ReaderModeOwner()
        val prepaid = Any(); val staff = Any()
        owner.claim(prepaid); owner.claim(staff)
        assertFalse(owner.holds(prepaid))        // the old screen's camera callback would install the wrong tag handler
        assertTrue(owner.holds(staff))
    }

    @Test fun returning_to_a_screen_claims_it_again() {
        val owner = ReaderModeOwner()
        val prepaid = Any(); val staff = Any()
        owner.claim(prepaid); owner.claim(staff)
        val prepaidAgain = Any()
        owner.claim(prepaidAgain)                // back navigation recomposes Prepaid
        assertFalse(owner.release(staff))
        assertTrue(owner.holds(prepaidAgain))
    }
}
