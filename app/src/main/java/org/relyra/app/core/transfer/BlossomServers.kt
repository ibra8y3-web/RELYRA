package org.relyra.app.core.transfer

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.relyra.app.core.network.AddRelayResult
import org.relyra.app.core.network.Http
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BlossomServer(val url: String, val enabled: Boolean, val signWithIdentity: Boolean)

sealed interface ServerStatus {
    data object Untested : ServerStatus
    data object Testing : ServerStatus
    class Reachable(val httpCode: Int, val ms: Long) : ServerStatus
    class Failed(val reason: String) : ServerStatus
}

/** The user's file servers. None by default; nothing connects until the user adds one. */
class BlossomServers(context: Context, private val http: OkHttpClient = Http.client) {
    private val prefs = context.applicationContext.getSharedPreferences("relyra_blossom", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<BlossomServer>> = _servers
    private val _status = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())
    val status: StateFlow<Map<String, ServerStatus>> = _status

    private fun load(): List<BlossomServer> =
        prefs.getString("servers", "").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split('\t')
            BlossomUrl.normalize(parts[0], allowPrivate = true)?.let { u ->
                BlossomServer(u, parts.getOrNull(1) != "0", parts.getOrNull(2) == "1")
            }
        }

    private fun save(list: List<BlossomServer>) {
        _servers.value = list
        prefs.edit {
            putString("servers", list.joinToString("\n") {
                "${it.url}\t${if (it.enabled) 1 else 0}\t${if (it.signWithIdentity) 1 else 0}"
            })
        }
    }

    fun add(input: String): AddRelayResult {
        val url = BlossomUrl.normalize(input, allowPrivate = true) ?: return AddRelayResult.Invalid
        if (_servers.value.any { it.url == url }) return AddRelayResult.Duplicate
        save(_servers.value + BlossomServer(url, enabled = true, signWithIdentity = false))
        return AddRelayResult.Added
    }

    fun remove(url: String) {
        save(_servers.value.filterNot { it.url == url })
        _status.value = _status.value - url
    }

    fun setEnabled(url: String, enabled: Boolean) =
        save(_servers.value.map { if (it.url == url) it.copy(enabled = enabled) else it })

    fun setSignWithIdentity(url: String, value: Boolean) =
        save(_servers.value.map { if (it.url == url) it.copy(signWithIdentity = value) else it })

    /** Reachability only: HTTPS connect + any HTTP answer. Whether uploads are accepted is only known by uploading. */
    fun test(url: String) {
        _status.value = _status.value + (url to ServerStatus.Testing)
        scope.launch {
            val start = System.nanoTime()
            val result: ServerStatus = try {
                val client = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url("$url/").head().build()).execute().use {
                    ServerStatus.Reachable(it.code, (System.nanoTime() - start) / 1_000_000)
                }
            } catch (e: IOException) {
                ServerStatus.Failed(e.message?.take(100) ?: e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                ServerStatus.Failed("invalid address")
            }
            _status.value = _status.value + (url to result)
        }
    }
}
