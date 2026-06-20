package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.db.PENDING_APPROVAL
import com.wyltek.wallet.agent.db.PENDING_DENIED
import com.wyltek.wallet.agent.db.PENDING_FAILED
import com.wyltek.wallet.agent.db.PENDING_SENT
import com.wyltek.wallet.agent.db.PendingIntentEntity
import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.core.native.Decision
import com.wyltek.wallet.core.native.Intent
import com.wyltek.wallet.core.native.LedgerView
import com.wyltek.wallet.core.native.RequestCtx
import com.wyltek.wallet.core.native.decide
import com.wyltek.wallet.data.WalletResult

sealed class DispatchResult {
    data class Sent(val txHash: String, val tokenId: String) : DispatchResult()
    data class Approval(val pendingId: Long, val tokenId: String, val asset: String, val amount: Long) : DispatchResult()
    data class Denied(val reason: String) : DispatchResult()
    data class Failed(val message: String) : DispatchResult()
}

/**
 * Turns an agent Intent into a policy decision and, on AllowAuto, an atomic
 * debit-then-broadcast. The send functions are injected (the concrete WalletRepository
 * is wired by AgentGateway) so this class is unit-testable with fakes.
 *
 * Atomicity model: reserve (durable DB row) BEFORE broadcast; confirm on success,
 * rollback (delete row) on failure. The unique (tokenId, nonce) index is the
 * hard concurrency/replay guard — reserve() returns null on conflict.
 */
class AgentActionDispatcher(
    private val keyStore: AgentKeyStore,
    private val ledger: AgentLedger,
    private val tokenService: AgentTokenService,
    private val pendingStore: PendingStore,
    private val resolveAccount: (bech32m: String) -> WalletAccount?,
    private val sendCkb: suspend (acct: WalletAccount, to: String, amount: Long) -> WalletResult<String>,
    private val sendToken: suspend (acct: WalletAccount, assetId: String, to: String, amount: Long) -> WalletResult<String>,
    private val daoDeposit: suspend (acct: WalletAccount, amount: Long) -> WalletResult<String>,
    private val daoWithdraw: suspend (acct: WalletAccount, daoRef: String) -> WalletResult<String>,
    private val daoClaim: suspend (acct: WalletAccount, daoRef: String) -> WalletResult<String>,
    private val messaging: MessagingSender
) {
    suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long): DispatchResult {
        // 1. Registry: token must be known and not revoked.
        val reg = tokenService.list().firstOrNull { it.token == token }
            ?: return DispatchResult.Denied("unknown token")
        if (reg.revoked) return DispatchResult.Denied("token revoked")

        // 2. Caps and ledger view; resolve the window length for cumulative accounting.
        val pub = keyStore.rootPublicHex()
        val caps = try { tokenService.caps(token) } catch (e: Throwable) {
            return DispatchResult.Denied("bad token: ${e.message}")
        }
        val cap = caps.firstOrNull { it.asset == intent.asset }
            ?: return DispatchResult.Denied("no cap for asset ${intent.asset}")

        val baseView = ledger.view(reg.tokenId, intent.asset, cap.windowSeconds, nowUnix)
        val view = LedgerView(
            cumulativeSpent = baseView.cumulativeSpent,
            windowSpent = baseView.windowSpent,
            nonceSeen = ledger.nonceSeen(reg.tokenId, intent.nonce)
        )

        // 3. Pure policy decision delegated to Rust (scope / account / TTL / allow_to / allow_ip + caps).
        val ctx = RequestCtx(account = reg.account, sourceIp = sourceIp, nowUnix = nowUnix)
        return when (val d = decide(token, pub, intent, view, ctx)) {
            is Decision.Deny -> DispatchResult.Denied(d.reason)
            is Decision.NeedApproval -> {
                val entity = PendingIntentEntity(
                    token = token,
                    op = intent.op,
                    asset = intent.asset,
                    to = intent.to,
                    amount = intent.amount,
                    nonce = intent.nonce,
                    action = intent.action,
                    daoRef = intent.daoRef,
                    sourceIp = sourceIp,
                    status = PENDING_APPROVAL,
                    createdAt = nowUnix
                )
                val rowId = pendingStore.put(entity)
                DispatchResult.Approval(pendingId = rowId, tokenId = d.tokenId, asset = d.asset, amount = d.amount)
            }
            is Decision.AllowAuto -> executeAuto(d.tokenId, reg.account, intent, nowUnix)
        }
    }

    suspend fun listPending() = pendingStore.listPending()

    /** Re-validates and executes a human-approved pending intent. */
    suspend fun executeApproved(pendingId: Long): DispatchResult {
        val p = pendingStore.get(pendingId) ?: return DispatchResult.Denied("pending intent not found")
        if (p.status != PENDING_APPROVAL) return DispatchResult.Denied("already ${p.status}")
        val intent = Intent(
            op = p.op,
            asset = p.asset,
            to = p.to,
            amount = p.amount,
            nonce = p.nonce,
            action = p.action,
            daoRef = p.daoRef
        )
        val reg = tokenService.list().firstOrNull { it.token == p.token }
            ?: return finishPending(pendingId, DispatchResult.Denied("unknown token"))
        if (reg.revoked) return finishPending(pendingId, DispatchResult.Denied("token revoked"))
        val now = nowSeconds()
        val pub = keyStore.rootPublicHex()
        val caps = try { tokenService.caps(p.token) } catch (e: Throwable) {
            return finishPending(pendingId, DispatchResult.Denied("bad token: ${e.message}"))
        }
        val cap = caps.firstOrNull { it.asset == p.asset }
            ?: return finishPending(pendingId, DispatchResult.Denied("no cap for asset ${p.asset}"))
        val base = ledger.view(reg.tokenId, p.asset, cap.windowSeconds, now)
        val view = LedgerView(
            cumulativeSpent = base.cumulativeSpent,
            windowSpent = base.windowSpent,
            nonceSeen = ledger.nonceSeen(reg.tokenId, p.nonce)
        )
        val ctx = RequestCtx(account = reg.account, sourceIp = p.sourceIp, nowUnix = now)
        // Approved intents may execute on AllowAuto OR NeedApproval (both = policy-valid within caps). Deny aborts.
        return when (val d = decide(p.token, pub, intent, view, ctx)) {
            is Decision.Deny -> finishPending(pendingId, DispatchResult.Denied(d.reason))
            else -> {
                val r = executeAuto(reg.tokenId, reg.account, intent, now)
                finishPending(pendingId, r)
            }
        }
    }

    private suspend fun finishPending(id: Long, r: DispatchResult): DispatchResult {
        when (r) {
            is DispatchResult.Sent -> pendingStore.setResult(id, PENDING_SENT, r.txHash, null)
            is DispatchResult.Denied -> pendingStore.setResult(id, PENDING_DENIED, null, r.reason)
            is DispatchResult.Failed -> pendingStore.setResult(id, PENDING_FAILED, null, r.message)
            else -> {}
        }
        return r
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    internal suspend fun executeAuto(
        tokenId: String,
        accountAddr: String,
        intent: Intent,
        nowUnix: Long
    ): DispatchResult {
        val account = resolveAccount(accountAddr)
            ?: return DispatchResult.Denied("account not found: $accountAddr")

        // Atomic reserve (durable debit) BEFORE broadcast; unique (tokenId, nonce) guards replay.
        val rowId = ledger.reserve(tokenId, intent.asset, intent.amount, nowUnix, intent.nonce)
            ?: return DispatchResult.Denied("replay: nonce ${intent.nonce} already used")

        val result = try {
            runAction(account, intent)
        } catch (e: Throwable) {
            ledger.rollback(rowId)
            return DispatchResult.Failed("send threw: ${e.message}")
        }

        return when (result) {
            is WalletResult.Success -> {
                ledger.confirm(rowId, result.data)
                DispatchResult.Sent(result.data, tokenId)
            }
            is WalletResult.Error -> {
                ledger.rollback(rowId)
                DispatchResult.Failed(result.message)
            }
        }
    }

    private suspend fun runAction(account: WalletAccount, intent: Intent): WalletResult<String> =
        when (intent.op) {
            "send_ckb" -> sendCkb(account, intent.to, intent.amount)
            "send_udt" -> sendToken(account, intent.asset, intent.to, intent.amount)
            "dao" -> when (intent.action) {
                "deposit", null -> daoDeposit(account, intent.amount)
                "withdraw" -> intent.daoRef?.let { daoWithdraw(account, it) }
                    ?: WalletResult.Error("dao withdraw requires dao_ref")
                "claim" -> intent.daoRef?.let { daoClaim(account, it) }
                    ?: WalletResult.Error("dao claim requires dao_ref")
                else -> WalletResult.Error("unknown dao action: ${intent.action}")
            }
            "messaging" -> messaging.send(account, intent.to, intent.amount, intent.action)
            else -> WalletResult.Error("unsupported op: ${intent.op}")
        }
}
