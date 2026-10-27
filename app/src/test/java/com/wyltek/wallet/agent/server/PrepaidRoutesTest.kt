package com.wyltek.wallet.agent.server

import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.core.native.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepaidRoutesTest {
    private fun port(journal: PrepaidJournalResult, cards: PrepaidCardsResult, staff: PrepaidStaffResult = PrepaidStaffResult.BadToken) = object : AgentDispatchPort {
        var seenToken: String? = null; var seenEntries: String? = null
        override suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = DispatchResult.Denied("n/a")
        override suspend fun pendingStatus(id: Long): IntentStatusResponse? = null
        override suspend fun accounts(): List<AccountInfo> = emptyList()
        override suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = "x"
        override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? = null
        override suspend fun proxyChainRead(token: String, method: String, rawBody: String) = ChainProxyResult.BadToken
        override suspend fun prepaidJournal(token: String, entriesHex: String, sourceIp: String, nowUnix: Long): PrepaidJournalResult { seenToken = token; seenEntries = entriesHex; return journal }
        override suspend fun prepaidCards(token: String, sourceIp: String, nowUnix: Long): PrepaidCardsResult { seenToken = token; return cards }
        override suspend fun prepaidStaff(token: String, sourceIp: String, nowUnix: Long) = staff
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

    @Test fun staff_ok_lists_keys_and_needs_a_token() = runBlocking {
        val p = port(PrepaidJournalResult.BadToken, PrepaidCardsResult.BadToken, PrepaidStaffResult.Ok(listOf(StaffKeyDto("77".repeat(8), 1, "ab".repeat(16)))))
        val r = routeAgentRequest("GET", "/v1/prepaid/staff", hdr, "", p)
        assertEquals(200, r.status)
        assertEquals("""{"staff":[{"key_id":"${"77".repeat(8)}","pin_ver":1,"pin_check":"${"ab".repeat(16)}"}]}""", r.body)
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/staff", noHdr, "", p).status)
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/staff", hdr, "", port(PrepaidJournalResult.BadToken, PrepaidCardsResult.BadToken)).status)
    }

    @Test fun staff_wire_never_carries_the_uid_or_the_signature() = runBlocking {
        val k = com.wyltek.wallet.prepaid.db.StaffKeyEntity("77".repeat(8), "04958caa5c5e80", "5a".repeat(32), "ring", 1, null, pinVer = 1, pinCheckHex = "ab".repeat(16), pinSetAt = 1)
        val p = port(PrepaidJournalResult.BadToken, PrepaidCardsResult.BadToken, PrepaidStaffResult.Ok(com.wyltek.wallet.agent.PrepaidGatewayMapping.staffDtosOf(listOf(k))))
        val body = routeAgentRequest("GET", "/v1/prepaid/staff", hdr, "", p).body
        assertEquals("""{"staff":[{"key_id":"${"77".repeat(8)}","pin_ver":1,"pin_check":"${"ab".repeat(16)}"}]}""", body)
        assertFalse(body.contains("04958caa5c5e80")); assertFalse(body.contains("5a".repeat(32)))
    }

    @Test fun staff_omits_a_key_without_a_pin_and_a_pinless_key_does_not_use_a_slot() {
        fun k(i: Int, pin: Boolean) = com.wyltek.wallet.prepaid.db.StaffKeyEntity("%016x".format(i), "04" + "%012x".format(i), "5a".repeat(32), "k$i", i.toLong(), null,
            pinVer = if (pin) 1 else 0, pinCheckHex = if (pin) "ab".repeat(16) else null, pinSetAt = if (pin) 1 else null)
        val keys = listOf(k(0, false)) + (1..16).map { k(it, true) } + listOf(k(17, true))
        val dtos = com.wyltek.wallet.agent.PrepaidGatewayMapping.staffDtosOf(keys)
        assertEquals(16, dtos.size); assertEquals((1..16).map { "%016x".format(it) }, dtos.map { it.keyId })
        assertTrue(dtos.all { it.pinVer == 1 && it.pinCheck == "ab".repeat(16) })
        assertTrue(com.wyltek.wallet.agent.PrepaidGatewayMapping.staffDtosOf(listOf(k(1, true).copy(revokedAt = 2))).isEmpty())
    }

    @Test fun staff_wire_carries_lowercase_hex() {
        val k = com.wyltek.wallet.prepaid.db.StaffKeyEntity("AB".repeat(8), "04958caa5c5e80", "5a".repeat(32), "ring", 1, null, pinVer = 2, pinCheckHex = "AB".repeat(16), pinSetAt = 1)
        val d = com.wyltek.wallet.agent.PrepaidGatewayMapping.staffDtoOf(k)!!
        assertEquals("ab".repeat(8), d.keyId); assertEquals("ab".repeat(16), d.pinCheck); assertEquals(2, d.pinVer)
    }
}
