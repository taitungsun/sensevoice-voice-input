package app.dsh.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App 層級的下載管理：模型下載跑在獨立執行緒，跨頁面持續進行；
 * 頁面註冊監聽取得進度（主執行緒回呼）。
 */
object DownloadBus {

    interface Listener {
        fun onProgress(modelId: String, done: Long, total: Long)
        fun onFinished(modelId: String, ok: Boolean, message: String?)
    }

    private const val TAG = "DownloadBus"
    private const val PROGRESS_MIN_INTERVAL_MS = 250L

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val flags = ConcurrentHashMap<String, AtomicBoolean>()
    private val main = Handler(Looper.getMainLooper())

    fun register(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun unregister(l: Listener) {
        listeners.remove(l)
    }

    fun isRunning(modelId: String): Boolean = flags.containsKey(modelId)

    fun cancel(modelId: String) {
        flags[modelId]?.set(true)
    }

    fun start(ctx: Context, model: AsrModel) {
        if (flags.containsKey(model.id)) return
        val appCtx = ctx.applicationContext
        val cancelFlag = AtomicBoolean(false)
        flags[model.id] = cancelFlag
        Thread({
            var ok = false
            var message: String? = null
            var lastPost = 0L
            try {
                val store = ModelStore(appCtx)
                ok = store.download(model, cancelFlag) { done, total, _ ->
                    val now = System.currentTimeMillis()
                    if (now - lastPost >= PROGRESS_MIN_INTERVAL_MS) {
                        lastPost = now
                        main.post { listeners.forEach { it.onProgress(model.id, done, total) } }
                    }
                }
                if (!ok) message = "網路或鏡像站異常（已重試 5 次）"
            } catch (e: DownloadCancelled) {
                message = "已取消"
            } catch (t: Throwable) {
                Log.w(TAG, "download failed: ${t.message}", t)
                message = t.message ?: t.javaClass.simpleName
            }
            flags.remove(model.id)
            val done = ok
            val msg = message
            main.post { listeners.forEach { it.onFinished(model.id, done, msg) } }
        }, "model-download").apply { isDaemon = true }.start()
    }
}
