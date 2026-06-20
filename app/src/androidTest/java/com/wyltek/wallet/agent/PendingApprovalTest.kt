package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.db.PENDING_APPROVAL
import com.wyltek.wallet.agent.db.PENDING_SENT
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.model.AccountType
import com.wyltek.wallet.core.model.AddressFormatVersion
import com.wyltek.wallet.core.model.CkbAddress
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.NetworkType
import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.core.native.CapInfo
import com.wyltek.wallet.core.native.Intent
import com.wyltek.wallet.core.native.Scope
import com.wyltek.wallet.core.native.TokenSpec
import com.wyltek.wallet.core.security.StrongBoxManager
import com.wyltek.wallet.data.WalletResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for durable pending-approval handling (Task 1, Plan B2).
 *
 * Requires a device or emulator because it exercises the Room/SQLCipher database.
 */
@RunWith(AndroidJUnit4::class)
class PendingApprovalTest {

    @Before
    fun clearDb() {
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .deleteDatabase("agent_gateway.db")
    }

    private val account = WalletAccount(
        id = "acc1",
        name = "test",
        type = AccountType.CLASSIC,
        network = NetworkType.TESTNET,
        addresses = listOf(
            CkbAddress(
                bech32m = "ckt1qfunded",
                lockScript = LockScript("0x00", "type", "0x00"),
                network = NetworkType.TESTNET,
                formatVersion = AddressFormatVersion.CKB2021
            )
        ),
        createdAt = 0
    )

    /** Spec with auto_limit=20, cumulative=100. Amounts >20 → NeedApproval. */
    private fun ckbSpec() = TokenSpec(
        account = "ckt1qfunded",
        scopes = listOf(Scope.SEND_CKB),
        caps = listOf(CapInfo(asset = "CKB", cumulative = 100L, windowSeconds = 0L, windowLimit = 0L, autoLimit = 20L)),
        ttlUnix = null,
        allowTo = emptyList(),
        allowIp = emptyList()
    )

    private fun intent(amount: Long, nonce: String) = Intent(
        op = "send_ckb",
        asset = "CKB",
        to = "ckt1qfunded",
        amount = amount,
        nonce = nonce,
        action = null,
        daoRef = null
    )

    private fun build(
        nextSendResult: () -> WalletResult<String>
    ): Triple<AgentActionDispatcher, AgentTokenService, PendingStore> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ks = AgentKeyStore(AgentSecureStore(ctx), StrongBoxManager(ctx))
        val db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 3 })
        val tokenSvc = AgentTokenService(ks, db)
        val ledger = AgentLedger(db)
        val pendingStore = PendingStore(db)
        val dispatcher = AgentActionDispatcher(
            keyStore = ks,
            ledger = ledger,
            tokenService = tokenSvc,
            pendingStore = pendingStore,
            resolveAccount = { addr -> if (addr == "ckt1qfunded") account else null },
            sendCkb = { _, _, _ -> nextSendResult() },
            sendToken = { _, _, _, _ -> nextSendResult() },
            daoDeposit = { _, _ -> nextSendResult() },
            daoWithdraw = { _, _ -> nextSendResult() },
            daoClaim = { _, _ -> nextSendResult() },
            messaging = UnsupportedMessagingSender()
        )
        return Triple(dispatcher, tokenSvc, pendingStore)
    }

    @Test
    fun dispatch_over_limit_returns_approval_with_pending_id() = runBlocking {
        val (d, svc, store) = build { WalletResult.Success("0xtx") }
        val m = svc.mint(ckbSpec())

        // 50 > auto_limit(20) → NeedApproval
        val r = d.dispatch(m.token, intent(50, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue("expected Approval, got $r", r is DispatchResult.Approval)
        val approval = r as DispatchResult.Approval
        assertTrue("pendingId must be > 0", approval.pendingId > 0)
        assertEquals("CKB", approval.asset)
        assertEquals(50L, approval.amount)
    }

    @Test
    fun list_pending_returns_one_after_approval_dispatch() = runBlocking {
        val (d, svc, store) = build { WalletResult.Success("0xtx") }
        val m = svc.mint(ckbSpec())

        d.dispatch(m.token, intent(50, "n1"), "10.0.0.1", 1_700_000_000)

        val pending = d.listPending()
        assertEquals(1, pending.size)
        assertEquals(PENDING_APPROVAL, pending[0].status)
        assertEquals(50L, pending[0].amount)
    }

    @Test
    fun execute_approved_sends_and_marks_sent() = runBlocking {
        val (d, svc, store) = build { WalletResult.Success("0xsent") }
        val m = svc.mint(ckbSpec())

        val r = d.dispatch(m.token, intent(50, "n1"), "10.0.0.1", 1_700_000_000)
        val approval = r as DispatchResult.Approval
        val pendingId = approval.pendingId

        val execResult = d.executeApproved(pendingId)
        assertTrue("expected Sent after executeApproved, got $execResult", execResult is DispatchResult.Sent)
        assertEquals("0xsent", (execResult as DispatchResult.Sent).txHash)

        // DB row should now be SENT
        val row = store.get(pendingId)
        assertEquals(PENDING_SENT, row?.status)
        assertEquals("0xsent", row?.resultTxHash)
    }

    @Test
    fun execute_approved_twice_returns_denied_already_sent() = runBlocking {
        val (d, svc, store) = build { WalletResult.Success("0xtx2") }
        val m = svc.mint(ckbSpec())

        val r = d.dispatch(m.token, intent(50, "n2"), "10.0.0.1", 1_700_000_000)
        val pendingId = (r as DispatchResult.Approval).pendingId

        d.executeApproved(pendingId) // first execute: Sent
        val r2 = d.executeApproved(pendingId) // second execute: Denied
        assertTrue("expected Denied on second executeApproved, got $r2", r2 is DispatchResult.Denied)
        assertTrue(
            "reason should mention 'already'",
            (r2 as DispatchResult.Denied).reason.contains("already")
        )
    }

    @Test
    fun execute_approved_denied_when_cumulative_cap_exceeded() = runBlocking {
        // cumulative cap is 100; pre-spend 60 via auto (≤20 each), then try approving 50 more
        val (d, svc, store) = build { WalletResult.Success("0xtxauto") }
        val m = svc.mint(ckbSpec())

        // Spend 20+20 = 40 via auto
        d.dispatch(m.token, intent(20, "auto1"), "10.0.0.1", 1_700_000_000)
        d.dispatch(m.token, intent(20, "auto2"), "10.0.0.1", 1_700_000_000)

        // Dispatch a 50-CKB over-limit intent → approval (cumulative would be 40+50=90, still ≤100)
        val r = d.dispatch(m.token, intent(50, "big1"), "10.0.0.1", 1_700_000_000)
        val pendingId = (r as DispatchResult.Approval).pendingId

        // Spend 60 more via auto so cumulative hits 100
        // Re-build with a fresh dispatcher sharing the same DB to spend 60 (3×20)
        val (d2, _, _) = build { WalletResult.Success("0xtxmore") }
        // reuse same token from svc - need dispatcher on same DB; use original d
        // spend 20 more to push cumulative to 60
        d.dispatch(m.token, intent(20, "auto3"), "10.0.0.1", 1_700_000_000)

        // Now cumulative is 60; approving 50 would push to 110, exceeding cap of 100 → Denied
        val execResult = d.executeApproved(pendingId)
        assertTrue(
            "expected Denied when cumulative cap exceeded, got $execResult",
            execResult is DispatchResult.Denied
        )
    }
}
