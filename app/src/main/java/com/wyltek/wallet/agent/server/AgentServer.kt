package com.wyltek.wallet.agent.server

import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.core.native.Intent
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Ktor application module wiring the Agent Gateway HTTP API.
 *
 * Routes:
 *   POST /v1/intent               — dispatch an agent intent, returns 200/202/403/502
 *   GET  /v1/intent/{id}          — poll a pending intent by row ID
 *   GET  /v1/account              — list all registered token accounts
 *   POST /relay/intent            — relay-compatible async facade: accept an intent,
 *                                    return {"status":"queued","intent_id":<id>} immediately
 *   GET  /relay/intent/{intent_id} — poll a /relay-facade intent by intent_id; 404 if unknown
 *
 * [port] is an [AgentDispatchPort] — on device it is an adapter over AgentGateway;
 * in host-JVM tests it is a pure in-memory fake.
 */
fun Application.agentModule(port: AgentDispatchPort) {
    install(ContentNegotiation) { json() }

    routing {
        post("/v1/intent") {
            val req = call.receive<IntentRequest>()
            val sourceIp = call.request.origin.remoteHost
            val intent = Intent(
                op = req.op,
                asset = req.asset,
                to = req.to,
                amount = req.amount,
                nonce = req.nonce,
                action = req.action,
                daoRef = req.daoRef
            )
            val now = System.currentTimeMillis() / 1000
            when (val d = port.dispatch(req.token, intent, sourceIp, now)) {
                is DispatchResult.Sent ->
                    call.respond(HttpStatusCode.OK, IntentResponse("sent", txHash = d.txHash))
                is DispatchResult.Approval ->
                    call.respond(HttpStatusCode.Accepted, IntentResponse("needs_approval", pendingId = d.pendingId))
                is DispatchResult.Denied ->
                    call.respond(HttpStatusCode.Forbidden, IntentResponse("denied", reason = d.reason))
                is DispatchResult.Failed ->
                    call.respond(HttpStatusCode.BadGateway, IntentResponse("failed", reason = d.message))
            }
        }

        get("/v1/intent/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, IntentStatusResponse("bad_id"))
            val status = port.pendingStatus(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, IntentStatusResponse("not_found"))
            call.respond(status)
        }

        get("/v1/account") {
            call.respond(port.accounts())
        }

        post("/relay/intent") {
            val req = call.receive<IntentRequest>()
            val sourceIp = call.request.origin.remoteHost
            val intent = Intent(
                op = req.op, asset = req.asset, to = req.to, amount = req.amount,
                nonce = req.nonce, action = req.action, daoRef = req.daoRef
            )
            val now = System.currentTimeMillis() / 1000
            val intentId = port.submitRelayIntent(req.token, intent, sourceIp, now)
            call.respond(HttpStatusCode.OK, RelayAcceptResponse("queued", intentId))
        }

        get("/relay/intent/{intent_id}") {
            val id = call.parameters["intent_id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, IntentStatusResponse("bad_id"))
            val status = port.relayIntentStatus(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, IntentStatusResponse("not_found"))
            call.respond(status)
        }
    }
}
