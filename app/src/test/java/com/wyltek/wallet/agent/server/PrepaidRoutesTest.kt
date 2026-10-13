package com.wyltek.wallet.agent.server

import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.core.native.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepaidRoutesTest {
    private fun port(journal: PrepaidJournalResult, cards: PrepaidCardsResult) = object : AgentDispatchPort {
        var seenToken: String? = null; var seenEntries: String? = null
        override suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = DispatchResult.Denied("n/a")
        override suspend fun pendingStatus(id: Long): IntentStatusResponse? = null
        override suspend fun accounts(): List<AccountInfo> = emptyList()
        override suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = "x"
        override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? = null
        override suspend fun proxyChainRead(token: String, method: String, rawBody: String) = ChainProxyResult.BadToken
        override suspend fun prepaidJournal(token: String, entriesHex: String, sourceIp: String, nowUnix: Long): PrepaidJournalResult { seenToken = token; seenEntries = entriesHex; return journal }
        override suspend fun prepaidCards(token: String, sourceIp: String, nowUnix: Long): PrepaidCardsResult { seenToken = token; return cards }
    }
    private val hdr: (String) -> String? = { if (it == "x-device-token") "tok" else null }
    private val noHdr: (String) -> String? = { null }

    @Test fun journal_ok_returns_per_entry_verdicts() = runBlocking {
        val p = port(PrepaidJournalResult.Ok(listOf(JournalVerdictDto(0, "accepted", "0xab"), JournalVerdictDto(1, "bad_mac"))), PrepaidCardsResult.BadToken)
        val r = routeAgentRequest("POST", "/v1/prepaid/journal", hdr, """{"entries_hex":"00ff"}""", p)
        assertEquals(200, r.status)
        val body = Json.decodeFromString(PrepaidJournalResponse.serializer(), r.body)
        assertEquals(listOf("accepted", "bad_mac"), body.results.map { it.verdict }); assertEquals("0xab", body.results[0].txHash)
        assertEquals("tok", p.seenToken); assertEquals("00ff", p.seenEntries)
    }

    @Test fun journal_bad_token_is_401_and_bad_body_is_400() = runBlocking {
        assertEquals(401, routeAgentRequest("POST", "/v1/prepaid/journal", noHdr, """{"entries_hex":"00"}""", port(PrepaidJournalResult.BadToken, PrepaidCardsResult.BadToken)).status)
        assertEquals(400, routeAgentRequest("POST", "/v1/prepaid/journal", hdr, "not json", port(PrepaidJournalResult.Ok(emptyList()), PrepaidCardsResult.BadToken)).status)
        assertEquals(400, routeAgentRequest("POST", "/v1/prepaid/journal", hdr, """{"entries_hex":"00"}""", port(PrepaidJournalResult.BadRequest("length"), PrepaidCardsResult.BadToken)).status)
    }

    @Test fun cards_lists_active_set() = runBlocking {
        val p = port(PrepaidJournalResult.BadToken, PrepaidCardsResult.Ok(listOf(PrepaidCardDto("aa", 1000, false), PrepaidCardDto("bb", 0, true))))
        val r = routeAgentRequest("GET", "/v1/prepaid/cards", hdr, "", p)
        assertEquals(200, r.status)
        val body = Json.decodeFromString(PrepaidCardsResponse.serializer(), r.body)
        assertEquals(2, body.cards.size); assertTrue(body.cards[1].revoked)
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/cards", noHdr, "", p).status)
    }

    @Test fun existing_fake_ports_still_compile_without_prepaid_overrides() = runBlocking {
        val p = object : AgentDispatchPort {
            override suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = DispatchResult.Denied("n/a")
            override suspend fun pendingStatus(id: Long): IntentStatusResponse? = null
            override suspend fun accounts(): List<AccountInfo> = emptyList()
            override suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = "x"
            override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? = null
            override suspend fun proxyChainRead(token: String, method: String, rawBody: String) = ChainProxyResult.BadToken
        }
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/cards", hdr, "", p).status)
    }
}
