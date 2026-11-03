package de.baschek.gymapp.core.api

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The device key pair used to prove possession on every authenticated request.
 * On Android the private key lives in the Keystore and cannot be exported;
 * tests use a software key. Implementations must be thread-safe.
 */
interface DeviceKey {
    /** SubjectPublicKeyInfo (X.509 DER) of the public key, base64url without padding. */
    fun publicKeySpkiBase64Url(): String

    /** ECDSA P-256 / SHA-256 signature, DER encoded. */
    fun sign(message: ByteArray): ByteArray
}

/** [DeviceKey] on top of JCA keys (software keys and Android Keystore keys alike). */
class JcaDeviceKey(private val privateKey: PrivateKey, private val publicKey: PublicKey) : DeviceKey {
    override fun publicKeySpkiBase64Url(): String = Base64Url.encode(publicKey.encoded)

    override fun sign(message: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(message)
        return signature.sign()
    }
}

object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

/** Headers added to every authenticated request. */
object SignedHeaders {
    const val TIMESTAMP = "X-Gym-Timestamp"
    const val NONCE = "X-Gym-Nonce"
    const val SIGNATURE = "X-Gym-Signature"
}

/** Builds the canonical string and the signature headers of the request signing scheme. */
class RequestSigner(
    private val key: DeviceKey,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nonce: () -> String = ::randomNonce,
) {
    /**
     * @param path request path without host (including any base path prefix)
     * @param query raw query string without `?`, or empty
     */
    fun headers(method: String, path: String, query: String, body: ByteArray): Map<String, String> {
        val timestamp = nowSeconds().toString()
        val n = nonce()
        val signature = key.sign(canonicalString(method, path, query, timestamp, n, body).toByteArray(Charsets.UTF_8))
        return mapOf(
            SignedHeaders.TIMESTAMP to timestamp,
            SignedHeaders.NONCE to n,
            SignedHeaders.SIGNATURE to Base64Url.encode(signature),
        )
    }

    companion object {
        /** `METHOD\nPATH\nQUERY\nTIMESTAMP\nNONCE\nSHA256_HEX(body)` */
        fun canonicalString(method: String, path: String, query: String, timestamp: String, nonce: String, body: ByteArray): String =
            listOf(method.uppercase(), path, query, timestamp, nonce, sha256Hex(body)).joinToString("\n")

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun randomNonce(): String = Base64Url.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })

        /** Verification counterpart (used by tests and useful for tooling). */
        fun verify(publicKeySpkiBase64Url: String, message: ByteArray, signatureBase64Url: String): Boolean = try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64Url.decode(publicKeySpkiBase64Url)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(message)
                verify(Base64Url.decode(signatureBase64Url))
            }
        } catch (e: Exception) {
            false
        }
    }
}
