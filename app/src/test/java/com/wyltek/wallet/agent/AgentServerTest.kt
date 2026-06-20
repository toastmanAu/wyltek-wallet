package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.server.AccountInfo
import com.wyltek.wallet.agent.server.AgentDispatchPort
import com.wyltek.wallet.agent.server.IntentRequest
import com.wyltek.wallet.agent.server.IntentResponse
import com.wyltek.wallet.agent.server.IntentStatusResponse
import com.wyltek.wallet.agent.server.agentModule
import com.wyltek.wallet.core.native.Intent
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        dispatchResult: DispatchResult
    ): AgentDispatchPort = object : AgentDispatchPort {
        override suspend fun dispatch(
            token: String,
            intent: Intent,
            sourceIp: String,
            nowUnix: Long
        ): DispatchResult = dispatchResult

        override suspend fun pendingStatus(id: Long): IntentStatusResponse? =
            if (id == 42L) storedStatus else null

        override suspend fun accounts(): List<AccountInfo> = accounts
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
}
