package com.wyltek.wallet.agent.server

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory

/**
 * NanoHTTPD-backed HTTPS server for the Agent Gateway. Ktor's CIO engine does not support
 * server-side TLS (`UnsupportedOperationException: CIO Engine does not currently support
 * HTTPS`) — NanoHTTPD is the Android-proven embedded-HTTPS replacement. All route logic lives
 * in [routeAgentRequest]; this class only adapts NanoHTTPD's request/response shape to it.
 */
class AgentHttpServer(
    hostname: String,
    port: Int,
    private val dispatchPort: AgentDispatchPort
) : NanoHTTPD(hostname, port) {

    override fun serve(session: IHTTPSession): Response {
        val method = session.method.name
        val path = session.uri
        val header: (String) -> String? = { name -> session.headers[name.lowercase()] }

        val bodyStr = if (method == "POST" || method == "PUT") {
            val files = mutableMapOf<String, String>()
            try {
                session.parseBody(files)
            } catch (e: Exception) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    ""
                )
            }
            files["postData"] ?: ""
        } else {
            ""
        }

        // Safety net: no route should ever throw out to NanoHTTPD (that resets the socket →
        // the client sees a connection error, not an HTTP status). Any unexpected throw → 500.
        val r = try {
            runBlocking {
                routeAgentRequest(method, path, header, bodyStr, dispatchPort, session.remoteIpAddress ?: "0.0.0.0")
            }
        } catch (e: Exception) {
            android.util.Log.e("AgentHttpServer", "route error for $method $path", e)
            RouteResult(500, "application/json", "")
        }

        return newFixedLengthResponse(statusFor(r.status), r.contentType, r.body)
    }

    /** NanoHTTPD's built-in [Response.Status] enum does not define every HTTP status code the
     *  router can return (notably 502 Bad Gateway, used for `DispatchResult.Failed` /
     *  `ChainProxyResult.Upstream`). [Response.Status.lookup] returns null for those, so fall
     *  back to a minimal custom [Response.IStatus] carrying the exact numeric code — the router
     *  and its tests assert on precise status codes, not on NanoHTTPD's enum coverage. */
    private fun statusFor(code: Int): Response.IStatus =
        Response.Status.lookup(code) ?: object : Response.IStatus {
            override fun getDescription(): String = "$code"
            override fun getRequestStatus(): Int = code
        }

    /** Configure this server to serve HTTPS using [keyStore]/[password] (the persisted
     *  self-signed keystore from [AgentTls]). Must be called before [start]. */
    fun secure(keyStore: KeyStore, password: CharArray) {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        makeSecure(makeSSLSocketFactory(keyStore, kmf), null)
    }
}
