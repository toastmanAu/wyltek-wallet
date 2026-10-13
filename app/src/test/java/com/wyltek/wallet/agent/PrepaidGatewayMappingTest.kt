package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.PrepaidGatewayMapping.Candidate
import com.wyltek.wallet.agent.PrepaidGatewayMapping.FundingChoice
import com.wyltek.wallet.agent.server.PrepaidCardDto
import com.wyltek.wallet.prepaid.db.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrepaidGatewayMappingTest {
    private val secp = PrepaidGatewayMapping.SECP256K1_BLAKE160_CODE_HASH
    private val pq = "0x" + "77".repeat(32)
    private fun card(state: String, kind: String? = null) = PrepaidCardEntity("r", "u", null, "0x1:0", "0x1:0", 500, 3, 2, state,
        pendingTxHash = kind?.let { "0x" + "ab".repeat(32) }, pendingKind = kind, issuedAt = 0, updatedAt = 0)

    @Test fun active_and_revoked_cards_are_listed() {
        assertEquals(PrepaidCardDto("r", 500, false), PrepaidGatewayMapping.cardDtoOf(card(CARD_ACTIVE)))
        assertEquals(PrepaidCardDto("r", 500, true), PrepaidGatewayMapping.cardDtoOf(card(CARD_REVOKED)))
        assertNull(PrepaidGatewayMapping.cardDtoOf(card(CARD_CLOSED)))
        assertNull(PrepaidGatewayMapping.cardDtoOf(card(CARD_ISSUING)))
    }
    @Test fun a_card_being_retired_is_reported_revoked() {
        assertEquals(true, PrepaidGatewayMapping.cardDtoOf(card(CARD_ACTIVE, KIND_VOID))!!.revoked)
        assertEquals(true, PrepaidGatewayMapping.cardDtoOf(card(CARD_ACTIVE, KIND_CASH_OUT))!!.revoked)
        assertEquals(false, PrepaidGatewayMapping.cardDtoOf(card(CARD_ACTIVE, KIND_LOAD))!!.revoked)
        assertEquals(false, PrepaidGatewayMapping.cardDtoOf(card(CARD_ACTIVE, KIND_SETTLE))!!.revoked)
    }

    @Test fun first_testnet_secp_account_with_a_seed_is_chosen_and_persisted() {
        val accts = listOf(Candidate("main", false, secp), Candidate("pq", true, pq), Candidate("noseed", true, secp),
            Candidate("good", true, secp), Candidate("later", true, secp))
        assertEquals(FundingChoice.Use("good", persist = true), PrepaidGatewayMapping.chooseFundingAccount(null, accts) { it != "noseed" })
    }
    @Test fun a_stored_account_wins_and_is_never_silently_switched() {
        val accts = listOf(Candidate("good", true, secp), Candidate("chosen", true, secp))
        assertEquals(FundingChoice.Use("chosen", persist = false), PrepaidGatewayMapping.chooseFundingAccount("chosen", accts) { true })
        assertEquals(FundingChoice.Unavailable(PrepaidGatewayMapping.REASON_MISSING), PrepaidGatewayMapping.chooseFundingAccount("deleted", accts) { true })
    }
    @Test fun no_eligible_account_is_unavailable() {
        val accts = listOf(Candidate("main", false, secp), Candidate("pq", true, pq), Candidate("empty", true, null))
        assertEquals(FundingChoice.Unavailable(PrepaidGatewayMapping.REASON_NO_ACCOUNT), PrepaidGatewayMapping.chooseFundingAccount(null, accts) { true })
    }
    @Test fun code_hash_match_is_case_insensitive() {
        assertEquals(FundingChoice.Use("a", true), PrepaidGatewayMapping.chooseFundingAccount(null, listOf(Candidate("a", true, secp.uppercase().replace("0X", "0x")))) { true })
    }

    @Test fun key_generation_is_announced_once_with_a_done_row() {
        val blobs = mutableMapOf<String, ByteArray>()
        val keys = com.wyltek.wallet.prepaid.PrepaidKeys(load = { blobs[it] }, store = { k, v -> blobs[k] = v }, random = { ByteArray(16) { 7 } })
        assertEquals(true, keys.ensureKDebit()); assertEquals(false, keys.ensureKDebit())
        val e = PrepaidGatewayMapping.keyGeneratedEvent(5, "id")
        assertEquals(KIND_KEY_GENERATED, e.kind); assertEquals(PHASE_DONE, e.phase); assertNull(e.reserveId); assertEquals("", e.uid)
        assertEquals("K_debit generated; re-provision every terminal", e.detail)
    }
}
