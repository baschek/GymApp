package de.baschek.gymapp.core.api

import java.net.URI

class InvalidBaseUrlException(val reason: String) : IllegalArgumentException(reason)

/** Validation of the server address typed in by the user at pairing. */
object BaseUrl {
    private val LOCAL_HOSTS = setOf("localhost", "10.0.2.2", "127.0.0.1")

    /**
     * Returns a normalized base URL without trailing slash. Only `https` is
     * accepted; with [allowInsecureLocal] (debug builds) plain `http` is
     * additionally allowed for local development hosts.
     */
    fun normalize(input: String, allowInsecureLocal: Boolean = false): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw InvalidBaseUrlException("empty")
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw InvalidBaseUrlException("malformed")
        }
        val host = uri.host?.lowercase() ?: throw InvalidBaseUrlException("no_host")
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw InvalidBaseUrlException("unsupported_parts")
        }
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> if (!(allowInsecureLocal && host in LOCAL_HOSTS)) throw InvalidBaseUrlException("https_required")
            else -> throw InvalidBaseUrlException("https_required")
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme.lowercase()}://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }
}
