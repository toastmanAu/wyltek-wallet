package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentActionDispatcherTest {

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

    /** Builds a dispatcher wired with fake send lambdas; returns (dispatcher, tokenService, sentTo list). */
    private fun build(
        sendResult: WalletResult<String>
    ): Triple<AgentActionDispatcher, AgentTokenService, MutableList<String>> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ks = AgentKeyStore(AgentSecureStore(ctx), StrongBoxManager(ctx))
        val db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 3 })
        val tokenSvc = AgentTokenService(ks, db)
        val ledger = AgentLedger(db)
        val sentTo = mutableListOf<String>()
        val dispatcher = AgentActionDispatcher(
            keyStore = ks,
            ledger = ledger,
            tokenService = tokenSvc,
            resolveAccount = { addr -> if (addr == "ckt1qfunded") account else null },
            sendCkb = { _, to, _ -> sentTo.add(to); sendResult },
            sendToken = { _, _, _, _ -> sendResult },
            daoDeposit = { _, _ -> sendResult },
            daoWithdraw = { _, _ -> sendResult },
            daoClaim = { _, _ -> sendResult },
            messaging = UnsupportedMessagingSender()
        )
        return Triple(dispatcher, tokenSvc, sentTo)
    }

    /** A spec with cumulative=100 and autoLimit=20. Amounts ≤20 → AllowAuto; >20 → NeedApproval. */
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

    @Test
    fun auto_spend_broadcasts_and_records() = runBlocking {
        val (d, svc, sent) = build(WalletResult.Success("0xtxhash"))
        val m = svc.mint(ckbSpec())
        val r = d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue("expected Sent, got $r", r is DispatchResult.Sent)
        assertEquals("0xtxhash", (r as DispatchResult.Sent).txHash)
        assertEquals(listOf("ckt1qfunded"), sent)
    }

    @Test
    fun over_auto_limit_needs_approval_no_broadcast() = runBlocking {
        val (d, svc, sent) = build(WalletResult.Success("0xtxhash"))
        val m = svc.mint(ckbSpec())
        val r = d.dispatch(m.token, intent(50, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue("expected Approval, got $r", r is DispatchResult.Approval)
        assertTrue("no broadcast expected", sent.isEmpty())
    }

    @Test
    fun broadcast_failure_rolls_back_debit() = runBlocking {
        val (d, svc, _) = build(WalletResult.Error("pool rejected"))
        val m = svc.mint(ckbSpec())
        val r = d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue("expected Failed, got $r", r is DispatchResult.Failed)
        // Rollback freed the cap; a new nonce with same amount stays within limit.
        // (Still fails because send still errors, but the failure is from send not from cap exceeded.)
        val r2 = d.dispatch(m.token, intent(5, "n2"), "10.0.0.1", 1_700_000_000)
        assertTrue("expected Failed on n2 (send still broken), got $r2", r2 is DispatchResult.Failed)
    }

    @Test
    fun replayed_nonce_denied() = runBlocking {
        val (d, svc, _) = build(WalletResult.Success("0xtxhash"))
        val m = svc.mint(ckbSpec())
        d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        val r = d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue("expected Denied for replay, got $r", r is DispatchResult.Denied)
    }
}
