package com.geminireader.tts

import com.geminireader.data.Settings
import com.geminireader.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class AccessToken(val value: String, val expiresAtMs: Long)
class TokenSession(private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var key = ""
    private var token: AccessToken? = null
    suspend fun get(config: String, rejected: String? = null, fetch: suspend () -> AccessToken): String = mutex.withLock {
        val current = token
        if (key == config && current != null && current.expiresAtMs > clock() + 60_000 && current.value != rejected) return@withLock current.value
        val updated = fetch()
        require(updated.value.isNotBlank() && updated.expiresAtMs > clock()) { "Token broker returned an expired token" }
        key = config; token = updated; updated.value
    }
}

object VertexAuth {
    private val session = TokenSession()
    fun validate(s: Settings) {
        if (s.vertexBrokerUrl.isBlank()) return
        val url = s.vertexBrokerUrl.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/token") { "Broker URL must be HTTPS and end in /token, without embedded credentials" }
        require(s.vertexBrokerPin.matches(Regex("[a-fA-F0-9]{64}"))) { "Enter the broker's SHA-256 certificate fingerprint" }
        require(s.vertexBrokerSecret.length >= 32) { "Enter the broker pairing secret" }
    }
    suspend fun token(s: Settings, rejected: String? = null): String {
        if (s.vertexBrokerUrl.isBlank()) {
            require(s.vertexToken.isNotBlank()) { "Configure automatic Vertex renewal or enter an access token" }
            return s.vertexToken
        }
        validate(s)
        return session.get("${s.vertexBrokerUrl}|${s.vertexBrokerPin}|${s.vertexBrokerSecret}|${s.vertexProject}", rejected) {
            withContext(Dispatchers.IO) {
                val trust = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client certificates unsupported") }
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        val cert = chain?.firstOrNull() ?: throw CertificateException("Missing broker certificate")
                        cert.checkValidity()
                        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
                        if (!digest.equals(s.vertexBrokerPin, true)) throw CertificateException("Broker certificate fingerprint changed")
                    }
                }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                // Dedicated client: trust pin applies only to the broker, never to Google API calls.
                val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
                    .followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
                val request = Request.Builder().url(s.vertexBrokerUrl).header("Authorization", "Bearer ${s.vertexBrokerSecret}")
                    .header("X-Reader-Refresh", if (rejected == null) "0" else "1").post(byteArrayOf().toRequestBody()).build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "Token renewal failed (HTTP ${response.code}); check your token broker and pairing" }
                    val result = json.parseToJsonElement(response.body.string()).jsonObject
                    require(result["project"]?.jsonPrimitive?.content == s.vertexProject) { "Broker project does not match Vertex project" }
                    val expires = result["expires_in"]!!.jsonPrimitive.long.coerceIn(1, 3600)
                    AccessToken(result["access_token"]!!.jsonPrimitive.content, System.currentTimeMillis() + expires * 1000)
                }
            }
        }
    }
    suspend fun request(api: HttpApi, s: Settings, url: String, body: JsonObject? = null): JsonObject {
        val token = token(s)
        try { return api.request(url, "", body, token, s.vertexProject) }
        catch (e: ApiFailure) {
            if (e.code != 401 || s.vertexBrokerUrl.isBlank()) throw e
            return api.request(url, "", body, token(s, rejected = token), s.vertexProject)
        }
    }
}
