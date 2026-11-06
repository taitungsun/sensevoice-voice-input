package app.dsh.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * F21: keeps the offline model loaded in this process.
 *
 * Loading SenseVoice takes ~9 s on the F21 (MT6761), and TT9 unbinds the
 * RecognitionService after every utterance, so without this every sentence
 * paid the full load. This foreground service keeps the process alive and the
 * recognizer in EngineHolder warm; it uses no CPU while idle.
 */
class KeeperService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification())
        warm(applicationContext)
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "語音模型常駐", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("離線語音輸入已就緒")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "KeeperService"
        private const val CHANNEL = "dsh_asr_keeper"
        private const val NOTIF_ID = 1003

        @Volatile private var warming = false

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, KeeperService::class.java)) }
                .onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        /** Loads the recognizer the RecognitionService will ask for (same model + "zh" key). */
        fun warm(ctx: Context) {
            if (warming) return
            warming = true
            Thread({
                try {
                    val model = ModelPicker.pick(Prefs(ctx), ModelStore(ctx), "zh")
                    if (model != null && !model.streaming) {
                        EngineHolder.offline(ctx, model, "zh")
                        TwConverter.convert(ctx, "預熱")
                        Log.i(TAG, "model warm: ${model.id}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "warm failed", t)
                } finally {
                    warming = false
                }
            }, "asr-warm").start()
        }
    }
}

/** Starts the keeper after boot and after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            KeeperService.start(context.applicationContext)
        }
    }
}
