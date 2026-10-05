package dev.anidroid

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class CatalogConnection(val url: String, val token: String, val fingerprint: String = "") {
    fun toJson() = JSONObject().put("url",url).put("token",token).put("fingerprint",fingerprint)
    companion object {
        fun fromJson(json: JSONObject) = CatalogConnection(json.getString("url"),json.getString("token"),json.optString("fingerprint"))
    }
}
class CatalogClient(val connection: CatalogConnection) {
    private val base = connection.url.toHttpUrl()
    private val client: OkHttpClient
    init {
        require(base.scheme == "https" && base.username.isEmpty() && base.password.isEmpty() && base.encodedPath == "/" && base.query == null && base.fragment == null) { "Enter an HTTPS server address without a path or credentials." }
        require(connection.token.length in 16..512 && connection.token.none { it.isWhitespace() }) { "Enter the server access key." }
        val pin=connection.fingerprint.replace(":", "").lowercase()
        require(pin.isEmpty() || pin.matches(Regex("[0-9a-f]{64}"))) { "Certificate fingerprint must contain 64 hexadecimal characters." }
        val builder=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(100,TimeUnit.SECONDS).callTimeout(105,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
        if(pin.isNotEmpty()) {
            val trust=object: X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                override fun checkClientTrusted(chain: Array<X509Certificate>,authType: String) { throw CertificateException("Client certificates are unsupported") }
                override fun checkServerTrusted(chain: Array<X509Certificate>,authType: String) {
                    val leaf=chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
                    leaf.checkValidity()
                    val actual=MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                    if(!MessageDigest.isEqual(actual.toByteArray(),pin.toByteArray())) throw CertificateException("Catalog server certificate does not match")
                }
            }
            val tls=SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
            // This private client trusts only the exact pinned certificate. The pin, not a
            // changeable LAN hostname, establishes server identity. Provider clients are untouched.
            builder.sslSocketFactory(tls.socketFactory,trust).hostnameVerifier { _,session ->
                runCatching { trust.checkServerTrusted(session.peerCertificates.map { it as X509Certificate }.toTypedArray(),"RSA");true }.getOrDefault(false)
            }
        }
        client=builder.build()
    }
    private fun get(path: String, params: Map<String,String> = emptyMap()): JSONObject {
        val url=base.newBuilder().encodedPath(path).apply { params.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        return client.newCall(Request.Builder().url(url).header("Authorization","Bearer ${connection.token}").build()).execute().use { r ->
            if(r.code == 401) error("Catalog access key was rejected. Check Server settings.")
            if(!r.isSuccessful) {
                val message=runCatching { JSONObject(r.body.string()).optString("error") }.getOrDefault("")
                error(message.ifBlank { "Catalog request failed (${r.code}). Try again shortly." })
            }
            JSONObject(r.body.string())
        }
    }
    fun catalog(query: String,provider: String,kind: String,page: Int,sort: String,genre: String = "") = get("/v1/catalog",mapOf("q" to query,"provider" to provider,"kind" to kind,"page" to "$page","sort" to sort,"genre" to genre))
    fun search(query: String,page: Int) = get("/v1/search",mapOf("provider" to "luffy","q" to query,"page" to "$page"))
    fun streams(title: Title,episode: Episode,source: String = "auto") = get("/v1/streams",mapOf("source" to source,"provider" to "luffy","id" to title.id,"season" to "${episode.season}","episode" to episode.number)).array("streams").objects().map(::streamFromJson)
    fun details(title: Title) = get("/v1/details",mapOf("provider" to title.provider,"id" to title.id))
}
