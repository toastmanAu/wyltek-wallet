package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.prepaid.PrepaidKeys
import com.wyltek.wallet.prepaid.PrepaidService
import com.wyltek.wallet.prepaid.VoucherService

val VOUCHER_TEST_LOCK = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))

/** One merchant with a 1000 CKB wallet cell, the card service and the voucher service over in-memory fakes. */
class VoucherHarness(walletCkb: ULong = 1000uL, val lock: LockScript = VOUCHER_TEST_LOCK) {
    val store = InMemoryPrepaidStore(); val vstore = InMemoryVoucherStore(); val chain = FakeChain(); val protocol = FakeProtocol()
    var now = 1_000L; var ids = 0
    val keys = PrepaidKeys(load = { null }, store = { _, _ -> }, random = { ByteArray(16) { 1 } })
    val core = PrepaidService(store, chain, FakeSigner(lock), protocol, keys, clock = { now++ }, newActionId = { "a${++ids}" })
    val svc = VoucherService(core, vstore)
    init { chain.add(Utxo(OutPoint("0x" + "aa".repeat(32), 0u), walletCkb * 100_000_000uL, lock)) }
}
