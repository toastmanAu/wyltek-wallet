package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.fakes.*
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test

class VoucherWiringTest {
    private fun core() = PrepaidService(InMemoryPrepaidStore(), FakeChain(), FakeSigner(VOUCHER_TEST_LOCK), FakeProtocol(),
        PrepaidKeys(load = { null }, store = { _, _ -> }, random = { ByteArray(16) { 1 } }))

    @Test fun a_freshly_built_prepaid_service_has_no_hooks_until_wired() {
        assertSame(NoVoucherHooks, core().voucherHooks)
    }

    @Test fun wiring_installs_the_voucher_service_as_the_hooks() {
        val c = core()
        val v = wireVoucherService(c, InMemoryVoucherStore())
        assertSame(v, c.voucherHooks)
        assertNotSame(NoVoucherHooks, c.voucherHooks)
    }
}
