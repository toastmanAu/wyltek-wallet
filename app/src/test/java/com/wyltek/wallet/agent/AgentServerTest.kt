package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.server.AccountInfo
import com.wyltek.wallet.agent.server.AgentDispatchPort
import com.wyltek.wallet.agent.server.ChainProxyResult
import com.wyltek.wallet.agent.server.IntentRequest
import com.wyltek.wallet.agent.server.IntentResponse
import com.wyltek.wallet.agent.server.IntentStatusResponse
import com.wyltek.wallet.agent.server.RelayAcceptResponse
import com.wyltek.wallet.agent.server.routeAgentRequest
import com.wyltek.wallet.core.native.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host-JVM test — calls [routeAgentRequest] directly, no HTTP server involved. */
class AgentServerTest {

    // ---------------------  fake port  ---------------------

    private val storedStatus = IntentStatusResponse(
        status = "sent",
        txHash = "0xabc123",
        error = null
    )

    private val accounts = listOf(
        AccountInfo(tokenId = "tok1", account = "ckb1abc", revoked = false),
        AccountInfo(tokenId = "tok2", account = "ckb1xyz", revoked = true)
    )

    /** Simple token→result dispatch table injected per test. */
    private fun fakePort(
        dispatchResult: DispatchResult,
        relayStatus: IntentStatusResponse? = IntentStatusResponse("sent", "0xrelay", null),
        proxyResult: ChainProxyResult = ChainProxyResult.Ok("""{"result":{"capacity":"0x123"}}"""),
    ): AgentDispatchPort = object : AgentDispatchPort {
        val submitted = mutableMapOf<String, IntentStatusResponse?>()

        override suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = dispatchResult
        override suspend fun pendingStatus(id: Long): IntentStatusResponse? = if (id == 42L) storedStatus else null
        override suspend fun accounts(): List<AccountInfo> = accounts

        override suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long): String {
            val id = "id-${intent.nonce}"                 // deterministic + idempotent per nonce
            submitted.putIfAbsent(id, relayStatus)
            return id
        }
        override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? =
            if (submitted.containsKey(intentId)) submitted[intentId] else null

        override suspend fun proxyChainRead(token: String, method: String, rawBody: String): ChainProxyResult {
            if (token != "good-token") return ChainProxyResult.BadToken
            if (method !in setOf("get_cells_capacity", "get_transactions")) return ChainProxyResult.BadMethod
            return proxyResult
        }
    }

    // ---------------------  helpers  -----------------------

    private val noHeader: (String) -> String? = { null }

    private val validReq = IntentRequest(
        token = "good-token",
        op = "send_ckb",
        asset = "CKB",
        to = "ckb1dest",
        amount = 100_000_000L,
        nonce = "n1"
    )

    private val validReqBody = Json.encodeToString(IntentRequest.serializer(), validReq)

    private fun ckbBody(method: String) =
        """{"id":42,"jsonrpc":"2.0","method":"$method","params":[]}"""

    private fun route(
        method: String,
        path: String,
        port: AgentDispatchPort,
        body: String = "",
        header: (String) -> String? = noHeader,
    ) = runBlocking { routeAgentRequest(method, path, header, body, port) }

    // ---------------------  tests  -------------------------

    @Test
    fun `within-cap intent returns 200 sent with txHash`() {
        val port = fakePort(DispatchResult.Sent("0xdeadbeef", "tok1"))
        val resp = route("POST", "/v1/intent", port, validReqBody)

        assertEquals(200, resp.status)
        val body = Json.decodeFromString(IntentResponse.serializer(), resp.body)
        assertEquals("sent", body.status)
        assertEquals("0xdeadbeef", body.txHash)
        assertNull(body.pendingId)
    }

    @Test
    fun `over-limit intent returns 202 needs_approval with pendingId`() {
        val port = fakePort(DispatchResult.Approval(pendingId = 7L, tokenId = "tok1", asset = "CKB", amount = 100L))
        val resp = route("POST", "/v1/intent", port, validReqBody)

        assertEquals(202, resp.status)
        val body = Json.decodeFromString(IntentResponse.serializer(), resp.body)
        assertEquals("needs_approval", body.status)
        assertEquals(7L, body.pendingId)
        assertNull(body.txHash)
    }

    @Test
    fun `bad token returns 403 denied`() {
        val port = fakePort(DispatchResult.Denied("unknown token"))
        val resp = route("POST", "/v1/intent", port, validReqBody)

        assertEquals(403, resp.status)
        val body = Json.decodeFromString(IntentResponse.serializer(), resp.body)
        assertEquals("denied", body.status)
        assertEquals("unknown token", body.reason)
    }

    @Test
    fun `GET intent id returns stored status`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        val resp = route("GET", "/v1/intent/42", port)

        assertEquals(200, resp.status)
        val body = Json.decodeFromString(IntentStatusResponse.serializer(), resp.body)
        assertEquals("sent", body.status)
        assertEquals("0xabc123", body.txHash)
    }

    @Test
    fun `GET intent unknown id returns 404`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        val resp = route("GET", "/v1/intent/999", port)

        assertEquals(404, resp.status)
    }

    @Test
    fun `GET account returns account list`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        val resp = route("GET", "/v1/account", port)

        assertEquals(200, resp.status)
        val body = Json.decodeFromString(ListSerializer(AccountInfo.serializer()), resp.body)
        assertEquals(2, body.size)
        assertEquals("tok1", body[0].tokenId)
        assertEquals("ckb1abc", body[0].account)
        assertEquals(false, body[0].revoked)
        assertEquals("tok2", body[1].tokenId)
        assertEquals(true, body[1].revoked)
    }

    @Test
    fun `POST relay intent returns 200 queued with intent_id`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        val resp = route("POST", "/relay/intent", port, validReqBody)

        assertEquals(200, resp.status)
        val body = Json.decodeFromString(RelayAcceptResponse.serializer(), resp.body)
        assertEquals("queued", body.status)
        assertEquals("id-n1", body.intentId)
    }

    @Test
    fun `POST relay intent is idempotent per nonce`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        val a = Json.decodeFromString(RelayAcceptResponse.serializer(), route("POST", "/relay/intent", port, validReqBody).body)
        val b = Json.decodeFromString(RelayAcceptResponse.serializer(), route("POST", "/relay/intent", port, validReqBody).body)
        assertEquals(a.intentId, b.intentId)
    }

    @Test
    fun `GET relay intent id returns mapped status`() {
        val port = fakePort(DispatchResult.Denied("n/a"), relayStatus = IntentStatusResponse("sent", "0xfeed", null))
        route("POST", "/relay/intent", port, validReqBody)
        val resp = route("GET", "/relay/intent/id-n1", port)

        assertEquals(200, resp.status)
        val body = Json.decodeFromString(IntentStatusResponse.serializer(), resp.body)
        assertEquals("sent", body.status); assertEquals("0xfeed", body.txHash)
    }

    @Test
    fun `GET relay intent unknown id returns 404`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        assertEquals(404, route("GET", "/relay/intent/nope", port).status)
    }

    @Test
    fun `relay ckb valid token and method returns 200 node body`() {
        val port = fakePort(DispatchResult.Denied("n/a"), proxyResult = ChainProxyResult.Ok("""{"result":{"capacity":"0xdead"}}"""))
        val resp = route("POST", "/relay/ckb", port, ckbBody("get_cells_capacity")) { name ->
            if (name == "x-device-token") "good-token" else null
        }

        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("0xdead"))
    }

    @Test
    fun `relay ckb bad token returns 401`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        val resp = route("POST", "/relay/ckb", port, ckbBody("get_cells_capacity")) { name ->
            if (name == "x-device-token") "wrong" else null
        }

        assertEquals(401, resp.status)
    }

    @Test
    fun `relay ckb non-whitelisted method returns 403`() {
        val port = fakePort(DispatchResult.Denied("n/a"))
        val resp = route("POST", "/relay/ckb", port, ckbBody("get_cells")) { name ->
            if (name == "x-device-token") "good-token" else null
        }

        assertEquals(403, resp.status)
    }

    @Test
    fun `relay ckb upstream failure returns 502`() {
        val port = fakePort(DispatchResult.Denied("n/a"), proxyResult = ChainProxyResult.Upstream("node down"))
        val resp = route("POST", "/relay/ckb", port, ckbBody("get_transactions")) { name ->
            if (name == "x-device-token") "good-token" else null
        }

        assertEquals(502, resp.status)
    }
}
