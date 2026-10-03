package com.openminis.app.tools

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.concurrent.Executors

/** Host transport, separated from orchestration for deterministic cancellation/failure tests. */
internal interface CodemodeTransport : AutoCloseable {
    suspend fun start(data: JSONObject)
    suspend fun receive(): JSONObject
    suspend fun settle(id: Int, success: Boolean, payload: String?)
}

/** A native QuickJS VM confined to one owner thread; only interrupt crosses threads. */
internal class CodemodeSandbox(private val context: Context) : CodemodeTransport {
    private val messages = Channel<JSONObject>(Channel.UNLIMITED)
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "codemode-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var closed = false
    private var handle = 0L
    private val native = CodemodeNative { event ->
        try { messages.trySend(JSONObject(event.toString(Charsets.UTF_8))) }
        catch (error: Exception) { crash(error) }
    }

    private fun crash(error: Throwable) {
        messages.trySend(JSONObject().put("type", "crash").put("message", error.toString()))
    }

    override suspend fun start(data: JSONObject) {
        val prelude = withContext(Dispatchers.IO) {
            context.assets.open("codemode/prelude.js").use { it.readBytes() }
        }
        val code = "(async (tools, console) => {${data.getString("code")}\n})".toByteArray(Charsets.UTF_8)
        val tools = data.getJSONArray("tools").toString().toByteArray(Charsets.UTF_8)
        val globals = data.getJSONArray("globals").toString().toByteArray(Charsets.UTF_8)
        val store = data.getJSONObject("store").toString().toByteArray(Charsets.UTF_8)
        scope.launch {
            try {
                val vm = synchronized(lock) {
                    if (closed) return@launch
                    native.create(prelude, tools, globals, store).also { handle = it }
                }
                native.start(vm, code)
            } catch (error: Throwable) { crash(error) }
        }
    }

    override suspend fun receive(): JSONObject = messages.receive()

    override suspend fun settle(id: Int, success: Boolean, payload: String?) {
        withContext(dispatcher) {
            val vm = synchronized(lock) { if (closed) 0L else handle }
            if (vm != 0L) native.settle(vm, id, success, payload?.toByteArray(Charsets.UTF_8))
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (handle != 0L) native.interrupt(handle)
            // Free only on the owner thread, after an interrupted evaluation has returned.
            scope.launch {
                val vm = synchronized(lock) { handle.also { handle = 0L } }
                try { if (vm != 0L) native.release(vm) }
                finally { messages.close(); scope.cancel(); dispatcher.close() }
            }
        }
    }
}

/** JNI uses bytes, not modified UTF-8 jstrings, so Unicode and JSON round trips are lossless. */
internal class CodemodeNative(private val event: (ByteArray) -> Unit) {
    fun onEvent(bytes: ByteArray) = event(bytes)
    external fun create(prelude: ByteArray, tools: ByteArray, globals: ByteArray, store: ByteArray): Long
    external fun start(handle: Long, code: ByteArray)
    external fun settle(handle: Long, id: Int, success: Boolean, payload: ByteArray?)
    external fun interrupt(handle: Long)
    external fun release(handle: Long)
    companion object { init { System.loadLibrary("codemode_jni") } }
}
