package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class OpenAIStreamCall(
    client: OkHttpClient,
    request: Request,
    private val uploadCapMs: Long,
    private val ttfbTimeoutMs: Long,
) {
    private val state = CallWatchState()
    private val call = client.newCall(request.newBuilder().tag(CallWatchState::class.java, state).build())

    fun execute(scope: CoroutineScope): Response {
        val timedOut = AtomicBoolean(false)
        val headersArrived = AtomicBoolean(false)
        val started = System.nanoTime()
        val watchdog = scope.launch {
            var phase: String? = null
            while (!headersArrived.get()) {
                val uploaded = state.uploadDoneAtNanos.get()
                val now = System.nanoTime()
                if (uploaded == 0L) {
                    if ((now - started) / 1_000_000L >= uploadCapMs) { phase = "upload"; break }
                } else if ((now - uploaded) / 1_000_000L >= ttfbTimeoutMs) { phase = "ttfb"; break }
                delay(250L)
            }
            if (phase != null && !headersArrived.get()) {
                timedOut.set(true)
                val seconds = (if (phase == "ttfb") ttfbTimeoutMs else uploadCapMs) / 1000
                AppLogger.warning("OpenAIProvider",
                    "[T-android-ttfb-upload-split] no response headers ($phase phase, ${seconds}s) — cancelling call + evicting connection (stale pooled connection?)")
                call.cancel()
                // Only this connection, never the pool shared by other sessions.
                try { state.connection.get()?.socket()?.close() } catch (_: Throwable) { }
            }
        }
        try {
            return call.execute()
        } catch (error: IOException) {
            if (timedOut.get()) throw LLMError.TransientError(
                "no response from server (${ttfbTimeoutMs / 1000}s TTFB) — check network/proxy")
            throw error
        } finally {
            headersArrived.set(true)
            watchdog.cancel()
        }
    }

    fun cancel() = call.cancel()
}

private class CallWatchState {
    val uploadDoneAtNanos = AtomicLong(0L)
    val connection = AtomicReference<Connection?>(null)
}

internal class OkHttpNetTraceListener : EventListener() {
    private val started = System.nanoTime()
    private fun event(call: Call, text: String, warning: Boolean = false) {
        val id = System.identityHashCode(call).toString(16)
        val line = "[call#$id] +${(System.nanoTime() - started) / 1_000_000L}ms $text"
        if (warning) AppLogger.warning("OkHttpNetTrace", line) else AppLogger.info("OkHttpNetTrace", line)
    }
    override fun callStart(call: Call) = event(call, "callStart url=${call.request().url}")
    override fun proxySelectStart(call: Call, url: HttpUrl) = event(call, "proxySelectStart host=${url.host}")
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) =
        event(call, "proxySelectEnd host=${url.host} chain=${proxies.joinToString(",") { it.toString() }}")
    override fun dnsStart(call: Call, domainName: String) = event(call, "dnsStart host=$domainName")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        event(call, "dnsEnd host=$domainName resolved=${inetAddressList.size} addrs=${inetAddressList.take(3).joinToString(",") { it.hostAddress ?: "?" }}")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        event(call, "connectStart target=$inetSocketAddress proxy=$proxy")
    override fun secureConnectStart(call: Call) = event(call, "tlsStart")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        event(call, "tlsEnd version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}")
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        event(call, "connectEnd target=$inetSocketAddress proxy=$proxy proto=$protocol")
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
        event(call, "connectFailed target=$inetSocketAddress proxy=$proxy proto=$protocol err=${ioe.javaClass.simpleName}:${ioe.message}", true)
    override fun connectionAcquired(call: Call, connection: Connection) {
        call.request().tag(CallWatchState::class.java)?.connection?.set(connection)
        event(call, "connectionAcquired conn#${System.identityHashCode(connection).toString(16)} route=${connection.route()} proto=${connection.protocol()}")
    }
    override fun connectionReleased(call: Call, connection: Connection) =
        event(call, "connectionReleased conn#${System.identityHashCode(connection).toString(16)}")
    override fun requestHeadersStart(call: Call) = event(call, "requestHeadersStart")
    override fun requestHeadersEnd(call: Call, request: Request) = event(call, "requestHeadersEnd")
    override fun requestBodyStart(call: Call) = event(call, "requestBodyStart")
    override fun requestBodyEnd(call: Call, byteCount: Long) {
        call.request().tag(CallWatchState::class.java)?.uploadDoneAtNanos?.set(System.nanoTime())
        event(call, "requestBodyEnd bytes=$byteCount")
    }
    override fun responseHeadersStart(call: Call) = event(call, "responseHeadersStart (server first byte)")
    override fun responseHeadersEnd(call: Call, response: Response) = event(call, "responseHeadersEnd status=${response.code} proto=${response.protocol}")
    override fun responseBodyStart(call: Call) = event(call, "responseBodyStart")
    override fun responseBodyEnd(call: Call, byteCount: Long) = event(call, "responseBodyEnd bytes=$byteCount")
    override fun callEnd(call: Call) = event(call, "callEnd")
    override fun callFailed(call: Call, ioe: IOException) = event(call, "callFailed err=${ioe.javaClass.simpleName}:${ioe.message}", true)
    override fun canceled(call: Call) = event(call, "canceled")
}
