package com.iaido.app

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class UnreachableReason { NO_NETWORK, CONNECT_OR_TIMEOUT, TLS_OR_IO, UNEXPECTED_STATUS, UNEXPECTED_BODY }

sealed interface CollectorReachability {
    /** The build has no collector address; no network call was made. */
    data object NotConfigured : CollectorReachability

    data object Reachable : CollectorReachability

    data class Unreachable(val reason: UnreachableReason, val statusCode: Int? = null) : CollectorReachability
}

/**
 * Probes the collector's unauthenticated `GET /v1/health`. It sends no credentials and no user
 * data, never provisions an installation, and never follows redirects (a captive portal's 302 is
 * reported as unreachable).
 */
class CollectorReachabilityProbe(
    private val baseUrl: String,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val totalTimeoutMs: Long = TIMEOUT_MS.toLong(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun checkReachability(): CollectorReachability {
        if (baseUrl.isBlank()) return CollectorReachability.NotConfigured
        val url = runCatching { URL(baseUrl.trim().trimEnd('/') + "/v1/health") }.getOrNull()
        if (url == null || !url.protocol.equals("https", ignoreCase = true) || url.host.isBlank()) {
            return CollectorReachability.NotConfigured
        }
        // Detached from the caller's scope on purpose: the blocking socket call cannot be
        // interrupted, so a structured child would keep the caller waiting past the deadline.
        val probe = CoroutineScope(SupervisorJob() + dispatcher).async { probe(url) }
        return withTimeoutOrNull(totalTimeoutMs) { probe.await() }
            ?: CollectorReachability.Unreachable(UnreachableReason.CONNECT_OR_TIMEOUT).also { probe.cancel() }
    }

    private fun probe(url: URL): CollectorReachability {
        val connection = try {
            connectionFactory(url)
        } catch (failure: IOException) {
            return failure.toUnreachable()
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) {
                CollectorReachability.Unreachable(UnreachableReason.UNEXPECTED_STATUS, status)
            } else if (connection.inputStream.use { it.readNBytes(MAX_BODY_BYTES) }.isHealthOk()) {
                CollectorReachability.Reachable
            } else {
                CollectorReachability.Unreachable(UnreachableReason.UNEXPECTED_BODY)
            }
        } catch (failure: IOException) {
            failure.toUnreachable()
        } catch (_: Exception) {
            CollectorReachability.Unreachable(UnreachableReason.TLS_OR_IO)
        } finally {
            connection.disconnect()
        }
    }

    private fun ByteArray.isHealthOk(): Boolean = runCatching {
        Json.parseToJsonElement(String(this, Charsets.UTF_8)).jsonObject["status"]?.jsonPrimitive?.content == "ok"
    }.getOrDefault(false)

    private fun IOException.toUnreachable(): CollectorReachability.Unreachable = CollectorReachability.Unreachable(
        when (this) {
            is UnknownHostException -> UnreachableReason.NO_NETWORK
            is SocketTimeoutException, is ConnectException, is NoRouteToHostException ->
                UnreachableReason.CONNECT_OR_TIMEOUT
            else -> UnreachableReason.TLS_OR_IO
        },
    )

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_BODY_BYTES = 1024
    }
}

/** Status line for the Settings section; null means a check is in flight. */
internal fun collectorStatusText(reachability: CollectorReachability?): String = when (reachability) {
    null -> "Collector: checking…"
    CollectorReachability.Reachable -> "Collector: reachable"
    CollectorReachability.NotConfigured -> TELEMETRY_UNAVAILABLE_MESSAGE
    is CollectorReachability.Unreachable -> "Collector: can't reach (${reachability.shortReason()})"
}

internal fun collectorStatusIsError(reachability: CollectorReachability?): Boolean =
    reachability is CollectorReachability.Unreachable || reachability == CollectorReachability.NotConfigured

private fun CollectorReachability.Unreachable.shortReason(): String = when (reason) {
    UnreachableReason.NO_NETWORK -> "no network or DNS failure"
    UnreachableReason.CONNECT_OR_TIMEOUT -> "connection failed or timed out"
    UnreachableReason.TLS_OR_IO -> "TLS or network error"
    UnreachableReason.UNEXPECTED_STATUS -> "unexpected response ${statusCode ?: ""}".trim()
    UnreachableReason.UNEXPECTED_BODY -> "unexpected response"
}
