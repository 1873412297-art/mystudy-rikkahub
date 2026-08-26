package me.rerere.rikkahub.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Disposable QuickJS worker. This service deliberately lives in a separate process: a JS busy loop
 * can only block this process, which the caller tears down after its deadline.
 */
class TavernScriptRunnerService : Service() {
    companion object {
        const val REQUEST = 1
        const val RESPONSE = 2
        const val SOURCE = "source"
        const val ARGS = "args"
        const val RESULT = "result"
    }

    private val thread = HandlerThread("TavernScriptRunner")
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread.start()
        messenger = Messenger(Handler(thread.looper) { message ->
            if (message.what != REQUEST) return@Handler true
            val data = message.data
            val result = runCatching { invoke(data.getString(SOURCE).orEmpty(), data.getString(ARGS).orEmpty()) }.getOrNull()
            message.replyTo?.send(Message.obtain(null, RESPONSE).apply {
                this.data = Bundle().apply { putString(RESULT, result) }
            })
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun invoke(source: String, args: String): String? {
        val context = QuickJSContext.create()
        return try {
            context.evaluate("var __rikkahub_runner = ($source);")
            context.evaluate("__rikkahub_runner(\"${escape(args)}\")")?.toString()
        } finally {
            context.destroy()
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}

/** Main-process client. A timeout is a recovery operation, not merely a cancelled Future. */
class TavernScriptRunnerClient(private val context: Context) {
    suspend fun invoke(source: String, args: String, timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val intent = Intent(context, TavernScriptRunnerService::class.java)
            var bound = false
            lateinit var connection: ServiceConnection
            fun cleanup() {
                if (bound) {
                    bound = false
                    runCatching { context.unbindService(connection) }
                }
                context.stopService(intent)
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    bound = true
                    val reply = Messenger(Handler(context.mainLooper) { response ->
                        if (response.what == TavernScriptRunnerService.RESPONSE && continuation.isActive) {
                            cleanup()
                            continuation.resume(response.data.getString(TavernScriptRunnerService.RESULT))
                        }
                        true
                    })
                    runCatching {
                        Messenger(binder).send(Message.obtain(null, TavernScriptRunnerService.REQUEST).apply {
                            data = Bundle().apply {
                                putString(TavernScriptRunnerService.SOURCE, source)
                                putString(TavernScriptRunnerService.ARGS, args)
                            }
                            replyTo = reply
                        })
                    }.onFailure { if (continuation.isActive) continuation.resume(null) }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    cleanup()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation {
                cleanup()
            }
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE) && continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
}
