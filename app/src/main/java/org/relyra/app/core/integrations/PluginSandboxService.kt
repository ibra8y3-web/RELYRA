package org.relyra.app.core.integrations

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.relyra.app.core.tools.BrowserUrlPolicy

/**
 * Isolated-process interpreter for declarative plugin actions.
 * It receives data only; it never loads plugin bytecode, scripts, reflection or native code.
 */
class PluginSandboxService : Service() {
    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == MSG_EXECUTE) execute(message)
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun execute(message: Message) {
        val input = message.data ?: Bundle()
        val type = input.getString("type").orEmpty()
        val title = input.getString("title").orEmpty().take(120)
        val text = input.getString("text").orEmpty().take(8192)
        val url = input.getString("url").orEmpty()
        val permissions = input.getStringArrayList("permissions").orEmpty().toSet()
        val result = when (type) {
            "notice" -> Bundle().apply { putString("kind", "notice"); putString("title", title); putString("text", text) }
            "copy_text" -> if ("clipboard.write" in permissions && text.isNotEmpty()) {
                Bundle().apply { putString("kind", "copy_text"); putString("text", text) }
            } else Bundle().apply { putString("kind", "blocked") }
            "open_url" -> {
                val safe = url.let(BrowserUrlPolicy::normalize)
                if ("external.open" in permissions && safe != null && safe.startsWith("https://", true)) {
                    Bundle().apply { putString("kind", "open_url"); putString("url", safe) }
                } else Bundle().apply { putString("kind", "blocked") }
            }
            else -> Bundle().apply { putString("kind", "blocked") }
        }
        runCatching {
            Message.obtain(null, MSG_RESULT).also {
                it.data = result
                message.replyTo?.send(it)
            }
        }
    }

    companion object {
        const val MSG_EXECUTE = 1
        const val MSG_RESULT = 2
    }
}
