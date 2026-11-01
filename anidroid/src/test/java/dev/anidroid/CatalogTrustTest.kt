package dev.anidroid

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.*
import org.junit.Test

class CatalogTrustTest {
    private fun cert(name: String) = javaClass.getResourceAsStream("/tls/$name.crt")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private fun pin(cert: X509Certificate) = MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded).joinToString("") { "%02x".format(it) }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Untrusted certificate was accepted") } catch (_: CertificateException) { }
    }
    @Test fun acceptsOnlyTheExactValidPinnedCertificate() {
        val leaf=cert("valid")
        verifyCatalogCertificate(arrayOf(leaf),pin(leaf))
        rejected { verifyCatalogCertificate(arrayOf(leaf),"0".repeat(64)) }
        rejected { verifyCatalogCertificate(emptyArray(),pin(leaf)) }
    }
    @Test fun aMatchingPinDoesNotBypassCertificateExpiry() {
        val expired=cert("expired")
        rejected { verifyCatalogCertificate(arrayOf(expired),pin(expired)) }
    }
    @Test fun connectionRejectsUnsafeAddressesAndMalformedPinsBeforeAnyRequest() {
        val key="test-access-key-00000000"
        for(url in listOf("http://local-test.invalid","https://user:pass@local-test.invalid", "https://local-test.invalid/path")) {
            try { CatalogClient(CatalogConnection(url,key));fail("Unsafe address accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { CatalogClient(CatalogConnection("https://local-test.invalid",key,"bad-pin"));fail("Malformed pin accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
