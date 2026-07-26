package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.server.AccountInfo
import com.wyltek.wallet.agent.server.AgentDispatchPort
import com.wyltek.wallet.agent.server.ChainProxyResult
import com.wyltek.wallet.agent.server.IntentRequest
import com.wyltek.wallet.agent.server.IntentResponse
import com.wyltek.wallet.agent.server.IntentStatusResponse
import com.wyltek.wallet.agent.server.RelayAcceptResponse
import com.wyltek.wallet.agent.server.agentModule
import com.wyltek.wallet.core.native.Intent
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host-JVM test — no Android runtime required. */
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

    private fun jsonClient(app: io.ktor.server.testing.ApplicationTestBuilder) =
        app.createClient {
            install(ContentNegotiation) { json() }
        }

    private val validReq = IntentRequest(
        token = "good-token",
        op = "send_ckb",
        asset = "CKB",
        to = "ckb1dest",
        amount = 100_000_000L,
        nonce = "n1"
    )

    private fun ckbBody(method: String) =
        """{"id":42,"jsonrpc":"2.0","method":"$method","params":[]}"""

    // ---------------------  tests  -------------------------

    @Test
    fun `within-cap intent returns 200 sent with txHash`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Sent("0xdeadbeef", "tok1"))) }
        val client = jsonClient(this)

        val resp = client.post("/v1/intent") {
            contentType(ContentType.Application.Json)
            setBody(validReq)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<IntentResponse>()
        assertEquals("sent", body.status)
        assertEquals("0xdeadbeef", body.txHash)
        assertNull(body.pendingId)
    }

    @Test
    fun `over-limit intent returns 202 needs_approval with pendingId`() = testApplication {
        application {
            agentModule(fakePort(DispatchResult.Approval(pendingId = 7L, tokenId = "tok1", asset = "CKB", amount = 100L)))
        }
        val client = jsonClient(this)

        val resp = client.post("/v1/intent") {
            contentType(ContentType.Application.Json)
            setBody(validReq)
        }

        assertEquals(HttpStatusCode.Accepted, resp.status)
        val body = resp.body<IntentResponse>()
        assertEquals("needs_approval", body.status)
        assertEquals(7L, body.pendingId)
        assertNull(body.txHash)
    }

    @Test
    fun `bad token returns 403 denied`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("unknown token"))) }
        val client = jsonClient(this)

        val resp = client.post("/v1/intent") {
            contentType(ContentType.Application.Json)
            setBody(validReq)
        }

        assertEquals(HttpStatusCode.Forbidden, resp.status)
        val body = resp.body<IntentResponse>()
        assertEquals("denied", body.status)
        assertEquals("unknown token", body.reason)
    }

    @Test
    fun `GET intent id returns stored status`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)

        val resp = client.get("/v1/intent/42")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<IntentStatusResponse>()
        assertEquals("sent", body.status)
        assertEquals("0xabc123", body.txHash)
    }

    @Test
    fun `GET intent unknown id returns 404`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)

        val resp = client.get("/v1/intent/999")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `GET account returns account list`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)

        val resp = client.get("/v1/account")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<List<AccountInfo>>()
        assertEquals(2, body.size)
        assertEquals("tok1", body[0].tokenId)
        assertEquals("ckb1abc", body[0].account)
        assertEquals(false, body[0].revoked)
        assertEquals("tok2", body[1].tokenId)
        assertEquals(true, body[1].revoked)
    }

    @Test
    fun `POST relay intent returns 200 queued with intent_id`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        val resp = client.post("/relay/intent") {
            contentType(ContentType.Application.Json); setBody(validReq)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<RelayAcceptResponse>()
        assertEquals("queued", body.status)
        assertEquals("id-n1", body.intentId)
    }

    @Test
    fun `POST relay intent is idempotent per nonce`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        val a = client.post("/relay/intent") { contentType(ContentType.Application.Json); setBody(validReq) }
            .body<RelayAcceptResponse>()
        val b = client.post("/relay/intent") { contentType(ContentType.Application.Json); setBody(validReq) }
            .body<RelayAcceptResponse>()
        assertEquals(a.intentId, b.intentId)
    }

    @Test
    fun `GET relay intent id returns mapped status`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"),
            relayStatus = IntentStatusResponse("sent", "0xfeed", null))) }
        val client = jsonClient(this)
        client.post("/relay/intent") { contentType(ContentType.Application.Json); setBody(validReq) }
        val resp = client.get("/relay/intent/id-n1")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<IntentStatusResponse>()
        assertEquals("sent", body.status); assertEquals("0xfeed", body.txHash)
    }

    @Test
    fun `GET relay intent unknown id returns 404`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        assertEquals(HttpStatusCode.NotFound, client.get("/relay/intent/nope").status)
    }

    @Test
    fun `relay ckb valid token and method returns 200 node body`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"),
            proxyResult = ChainProxyResult.Ok("""{"result":{"capacity":"0xdead"}}"""))) }
        val client = jsonClient(this)
        val resp = client.post("/relay/ckb") {
            headers { append("x-device-token", "good-token") }
            contentType(ContentType.Application.Json); setBody(ckbBody("get_cells_capacity"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("0xdead"))
    }

    @Test
    fun `relay ckb bad token returns 401`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        val resp = client.post("/relay/ckb") {
            headers { append("x-device-token", "wrong") }
            contentType(ContentType.Application.Json); setBody(ckbBody("get_cells_capacity"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `relay ckb non-whitelisted method returns 403`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        val resp = client.post("/relay/ckb") {
            headers { append("x-device-token", "good-token") }
            contentType(ContentType.Application.Json); setBody(ckbBody("get_cells"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `relay ckb upstream failure returns 502`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"),
            proxyResult = ChainProxyResult.Upstream("node down"))) }
        val client = jsonClient(this)
        val resp = client.post("/relay/ckb") {
            headers { append("x-device-token", "good-token") }
            contentType(ContentType.Application.Json); setBody(ckbBody("get_transactions"))
        }
        assertEquals(HttpStatusCode.BadGateway, resp.status)
    }
}
