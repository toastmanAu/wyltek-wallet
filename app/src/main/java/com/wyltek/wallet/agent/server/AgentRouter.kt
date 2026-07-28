package com.wyltek.wallet.agent.server

import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.core.native.Intent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result of routing a single HTTP request through [routeAgentRequest]. The HTTP server
 * (currently [AgentHttpServer], NanoHTTPD-backed) is responsible for turning this into an
 * actual wire response; this type keeps that translation trivial and testable without any
 * HTTP-server dependency at all.
 */
data class RouteResult(val status: Int, val contentType: String = "application/json", val body: String = "")

private val json = Json { ignoreUnknownKeys = true }

/**
 * Pure router for the Agent Gateway HTTP API — the single source of truth for routes,
 * status codes, and response bodies. No HTTP-server dependency: callable directly from
 * host-JVM tests, and wrapped by [AgentHttpServer] on device.
 *
 * Routes:
 *   POST /v1/intent               — dispatch an agent intent, returns 200/202/403/502
 *   GET  /v1/intent/{id}          — poll a pending intent by row ID
 *   GET  /v1/account              — list all registered token accounts
 *   POST /relay/intent            — relay-compatible async facade: accept an intent,
 *                                    return {"status":"queued","intent_id":<id>} immediately
 *   GET  /relay/intent/{intent_id} — poll a /relay-facade intent by intent_id; 404 if unknown
 *   POST /relay/ckb                — token-gated, method-whitelisted CKB read-RPC proxy
 *
 * [port] is an [AgentDispatchPort] — on device it is an adapter over AgentGateway;
 * in host-JVM tests it is a pure in-memory fake.
 *
 * [sourceIp] is advisory only (passed through to [AgentDispatchPort.dispatch] /
 * [AgentDispatchPort.submitRelayIntent]) — the token is what actually gates access.
 */
suspend fun routeAgentRequest(
    method: String,
    path: String,
    header: (String) -> String?,
    body: String,
    port: AgentDispatchPort,
    sourceIp: String = "0.0.0.0"
): RouteResult {
    val relayIntentIdRegex = Regex("^/relay/intent/(.+)$")
    val intentIdRegex = Regex("^/v1/intent/(.+)$")

    return when {
        method == "POST" && path == "/v1/intent" -> {
            val req = try {
                json.decodeFromString(IntentRequest.serializer(), body)
            } catch (e: Exception) {
                return result(400, IntentStatusResponse("bad_request"))
            }
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
                    ok(IntentResponse("sent", txHash = d.txHash))
                is DispatchResult.Approval ->
                    result(202, IntentResponse("needs_approval", pendingId = d.pendingId))
                is DispatchResult.Denied ->
                    result(403, IntentResponse("denied", reason = d.reason))
                is DispatchResult.Failed ->
                    result(502, IntentResponse("failed", reason = d.message))
            }
        }

        method == "GET" && intentIdRegex.matches(path) -> {
            val idStr = intentIdRegex.matchEntire(path)!!.groupValues[1]
            val id = idStr.toLongOrNull()
                ?: return result(400, IntentStatusResponse("bad_id"))
            val status = port.pendingStatus(id)
                ?: return result(404, IntentStatusResponse("not_found"))
            ok(status)
        }

        method == "GET" && path == "/v1/account" ->
            ok(port.accounts(), ListSerializers.accountInfoList)

        method == "POST" && path == "/relay/intent" -> {
            val req = try {
                json.decodeFromString(IntentRequest.serializer(), body)
            } catch (e: Exception) {
                return result(400, IntentStatusResponse("bad_request"))
            }
            val intent = Intent(
                op = req.op, asset = req.asset, to = req.to, amount = req.amount,
                nonce = req.nonce, action = req.action, daoRef = req.daoRef
            )
            val now = System.currentTimeMillis() / 1000
            val intentId = port.submitRelayIntent(req.token, intent, sourceIp, now)
            ok(RelayAcceptResponse("queued", intentId))
        }

        method == "GET" && relayIntentIdRegex.matches(path) -> {
            val id = relayIntentIdRegex.matchEntire(path)!!.groupValues[1]
            val status = port.relayIntentStatus(id)
                ?: return result(404, IntentStatusResponse("not_found"))
            ok(status)
        }

        method == "POST" && path == "/relay/ckb" -> {
            val token = header("x-device-token") ?: ""
            val rpcMethod = try {
                Json.parseToJsonElement(body).jsonObject["method"]?.jsonPrimitive?.content ?: ""
            } catch (e: Exception) { "" }
            when (val r = port.proxyChainRead(token, rpcMethod, body)) {
                is ChainProxyResult.Ok -> RouteResult(200, "application/json", r.body)
                ChainProxyResult.BadToken -> RouteResult(401)
                ChainProxyResult.BadMethod -> RouteResult(403)
                is ChainProxyResult.Upstream -> RouteResult(502)
            }
        }

        else -> RouteResult(404)
    }
}

private fun ok(response: IntentResponse) = result(200, response)
private fun ok(response: IntentStatusResponse) = result(200, response)
private fun ok(response: RelayAcceptResponse) = result(200, response)
private fun ok(response: List<AccountInfo>, serializer: kotlinx.serialization.KSerializer<List<AccountInfo>>) =
    RouteResult(200, "application/json", json.encodeToString(serializer, response))

private fun result(status: Int, response: IntentResponse) =
    RouteResult(status, "application/json", json.encodeToString(IntentResponse.serializer(), response))

private fun result(status: Int, response: IntentStatusResponse) =
    RouteResult(status, "application/json", json.encodeToString(IntentStatusResponse.serializer(), response))

private fun result(status: Int, response: RelayAcceptResponse) =
    RouteResult(status, "application/json", json.encodeToString(RelayAcceptResponse.serializer(), response))

/** kotlinx.serialization needs an explicit serializer for List<AccountInfo>; kept in one place. */
private object ListSerializers {
    val accountInfoList = kotlinx.serialization.builtins.ListSerializer(AccountInfo.serializer())
}
