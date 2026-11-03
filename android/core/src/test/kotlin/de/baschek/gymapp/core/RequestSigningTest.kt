package de.baschek.gymapp.core

import de.baschek.gymapp.core.api.Base64Url
import de.baschek.gymapp.core.api.JcaDeviceKey
import de.baschek.gymapp.core.api.RequestSigner
import de.baschek.gymapp.core.api.SignedHeaders
import de.baschek.gymapp.core.sync.ReconnectBackoff
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Software P-256 key for tests; production uses a Keystore key behind the same interface. */
fun signingKey(): JcaDeviceKey {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    return JcaDeviceKey(pair.private, pair.public)
}

class RequestSigningTest {
    @Test
    fun canonicalStringTestVectorForGet() {
        val canonical = RequestSigner.canonicalString(
            "get", "/gym/v1/changes", "since=5&limit=500", "1700000000", "AAAAAAAAAAAAAAAAAAAAAA", ByteArray(0),
        )
        assertEquals(
            "GET\n/gym/v1/changes\nsince=5&limit=500\n1700000000\nAAAAAAAAAAAAAAAAAAAAAA\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            canonical,
        )
    }

    @Test
    fun canonicalStringTestVectorForPostWithBody() {
        val canonical = RequestSigner.canonicalString("POST", "/gym/v1/push", "", "1700000001", "bm9uY2U", """{"ops":[]}""".toByteArray())
        assertEquals(
            "POST\n/gym/v1/push\n\n1700000001\nbm9uY2U\nb2f0effc1a37cecc88986e93381ca24c017e5b7a288ea14a9462ae9b4c466f0c",
            canonical,
        )
    }

    @Test
    fun signatureVerifiesWithPublicKeyAndNotWithChangedInput() {
        val key = signingKey()
        val signer = RequestSigner(key, { 1_700_000_000L }, { "nonce-1" })
        val h = signer.headers("POST", "/gym/v1/push", "", """{"ops":[]}""".toByteArray())
        val canonical = RequestSigner.canonicalString("POST", "/gym/v1/push", "", "1700000000", "nonce-1", """{"ops":[]}""".toByteArray())
        val sig = h.getValue(SignedHeaders.SIGNATURE)
        assertTrue(RequestSigner.verify(key.publicKeySpkiBase64Url(), canonical.toByteArray(), sig))
        assertFalse(RequestSigner.verify(key.publicKeySpkiBase64Url(), canonical.replace("push", "pull").toByteArray(), sig))
        assertFalse(RequestSigner.verify(signingKey().publicKeySpkiBase64Url(), canonical.toByteArray(), sig))
        assertEquals("1700000000", h[SignedHeaders.TIMESTAMP])
        assertEquals("nonce-1", h[SignedHeaders.NONCE])
    }

    @Test
    fun publicKeyIsSpkiBase64UrlWithoutPadding() {
        val encoded = signingKey().publicKeySpkiBase64Url()
        assertFalse(encoded.contains('=') || encoded.contains('+') || encoded.contains('/'))
        val der = Base64Url.decode(encoded)
        assertEquals(91, der.size) // SPKI of an uncompressed P-256 point
        assertEquals(0x30, der[0].toInt())
    }

    @Test
    fun noncesAreRandomAndLongEnough() {
        val a = RequestSigner.randomNonce()
        assertTrue(Base64Url.decode(a).size >= 16)
        assertFalse(a == RequestSigner.randomNonce())
    }

    @Test
    fun backoffGrowsExponentiallyAndIsCapped() {
        assertEquals(15 * 60_000L, ReconnectBackoff.delayMillis(1))
        assertEquals(30 * 60_000L, ReconnectBackoff.delayMillis(2))
        assertEquals(60 * 60_000L, ReconnectBackoff.delayMillis(3))
        assertEquals(24 * 3_600_000L, ReconnectBackoff.delayMillis(50))
    }
}
