package com.iaido.app

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectorReachabilityTest {
    private val baseUrl = "https://collector.example"

    @Test
    fun `blank url is not configured and opens no connection`() = runBlocking {
        val probe = CollectorReachabilityProbe("  ", { error("no connection expected") })
        assertEquals(CollectorReachability.NotConfigured, probe.checkReachability())
    }

    @Test
    fun `200 with status ok is reachable and sends a bare GET to health`() = runBlocking {
        val connection = FakeHealthConnection(200, """{"status":"ok"}""")
        val result = CollectorReachabilityProbe(baseUrl, { url -> assertEquals("/v1/health", url.path); connection })
            .checkReachability()

        assertEquals(CollectorReachability.Reachable, result)
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertFalse(connection.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(connection.doOutput)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `500 is unreachable with the status code`() = runBlocking {
        val result = probe(FakeHealthConnection(500, "boom")).checkReachability()
        assertEquals(CollectorReachability.Unreachable(UnreachableReason.UNEXPECTED_STATUS, 500), result)
    }

    @Test
    fun `captive portal 302 is not followed and is unreachable`() = runBlocking {
        val result = probe(FakeHealthConnection(302, "")).checkReachability()
        assertEquals(CollectorReachability.Unreachable(UnreachableReason.UNEXPECTED_STATUS, 302), result)
    }

    @Test
    fun `200 without status ok is an unexpected response`() = runBlocking {
        val result = probe(FakeHealthConnection(200, "<html>portal</html>")).checkReachability()
        assertEquals(CollectorReachability.Unreachable(UnreachableReason.UNEXPECTED_BODY), result)
    }

    @Test
    fun `dns failure means no network`() = runBlocking {
        val result = probe(FakeHealthConnection(failure = UnknownHostException("collector.example"))).checkReachability()
        assertEquals(CollectorReachability.Unreachable(UnreachableReason.NO_NETWORK), result)
    }

    @Test
    fun `socket timeout is a connect timeout`() = runBlocking {
        val result = probe(FakeHealthConnection(failure = SocketTimeoutException("timed out"))).checkReachability()
        assertEquals(CollectorReachability.Unreachable(UnreachableReason.CONNECT_OR_TIMEOUT), result)
    }

    @Test
    fun `connection refused is a connect failure`() = runBlocking {
        val result = probe(FakeHealthConnection(failure = java.net.ConnectException("refused"))).checkReachability()
        assertEquals(CollectorReachability.Unreachable(UnreachableReason.CONNECT_OR_TIMEOUT), result)
    }

    @Test
    fun `tls and other io errors are reported as tls or io`() = runBlocking {
        assertEquals(
            CollectorReachability.Unreachable(UnreachableReason.TLS_OR_IO),
            probe(FakeHealthConnection(failure = SSLHandshakeException("bad cert"))).checkReachability(),
        )
        assertEquals(
            CollectorReachability.Unreachable(UnreachableReason.TLS_OR_IO),
            probe(FakeHealthConnection(failure = IOException("reset"))).checkReachability(),
        )
    }

    @Test
    fun `a hung connection is cut off by the overall timeout`() = runBlocking {
        val hung = FakeHealthConnection(blockMs = 3_000)
        val started = System.nanoTime()
        val result = CollectorReachabilityProbe(baseUrl, { hung }, totalTimeoutMs = 100).checkReachability()

        assertEquals(CollectorReachability.Unreachable(UnreachableReason.CONNECT_OR_TIMEOUT), result)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun `status text covers every state`() {
        assertEquals("Collector: checking…", collectorStatusText(null))
        assertEquals("Collector: reachable", collectorStatusText(CollectorReachability.Reachable))
        assertEquals(TELEMETRY_UNAVAILABLE_MESSAGE, collectorStatusText(CollectorReachability.NotConfigured))
        assertEquals(
            "Collector: can't reach (no network or DNS failure)",
            collectorStatusText(CollectorReachability.Unreachable(UnreachableReason.NO_NETWORK)),
        )
        assertEquals(
            "Collector: can't reach (connection failed or timed out)",
            collectorStatusText(CollectorReachability.Unreachable(UnreachableReason.CONNECT_OR_TIMEOUT)),
        )
        assertEquals(
            "Collector: can't reach (TLS or network error)",
            collectorStatusText(CollectorReachability.Unreachable(UnreachableReason.TLS_OR_IO)),
        )
        assertEquals(
            "Collector: can't reach (unexpected response 302)",
            collectorStatusText(CollectorReachability.Unreachable(UnreachableReason.UNEXPECTED_STATUS, 302)),
        )
        assertEquals(
            "Collector: can't reach (unexpected response)",
            collectorStatusText(CollectorReachability.Unreachable(UnreachableReason.UNEXPECTED_BODY)),
        )
    }

    @Test
    fun `only unreachable is an error state`() {
        assertTrue(collectorStatusIsError(CollectorReachability.Unreachable(UnreachableReason.TLS_OR_IO)))
        assertTrue(collectorStatusIsError(CollectorReachability.NotConfigured))
        assertFalse(collectorStatusIsError(CollectorReachability.Reachable))
        assertFalse(collectorStatusIsError(null))
    }

    private fun probe(connection: FakeHealthConnection) =
        CollectorReachabilityProbe(baseUrl, { connection })
}

private class FakeHealthConnection(
    private val code: Int = 200,
    body: String = "",
    private val failure: IOException? = null,
    private val blockMs: Long = 0,
) : HttpURLConnection(URL("https://collector.example/v1/health")) {
    val headers = mutableMapOf<String, String>()
    private val bytes = body.toByteArray()
    var disconnected = false

    override fun setRequestProperty(key: String, value: String) {
        headers[key] = value
    }

    override fun getResponseCode(): Int {
        if (blockMs > 0) Thread.sleep(blockMs)
        failure?.let { throw it }
        return code
    }

    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun connect() = Unit
}
