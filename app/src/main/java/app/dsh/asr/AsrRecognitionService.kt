package app.dsh.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 系統級離線語音辨識服務。
 *
 * 任何 App 透過 `android.speech.SpeechRecognizer`（預設辨識服務）就能呼叫本服務，
 * 辨識全程在本機完成（sherpa-onnx + 本機模型），不連網、不上傳音訊。
 */
class AsrRecognitionService : RecognitionService() {

    @Volatile
    private var session: CaptureSession? = null

    private val lock = Any()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // F21: load the s2tw dictionaries in the background so the first result is not delayed.
        // and make sure the keeper holds the model loaded (it may have been stopped).
        KeeperService.start(applicationContext)
        Log.i(TAG, "service created")
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val prefs = Prefs(this)
        val params = parseParams(recognizerIntent, prefs)
        val store = ModelStore(this)
        val model = ModelPicker.pick(prefs, store, params.langHint)
        Log.i(TAG, "onStartListening lang=${params.langHint} partials=${params.partialsWanted} model=${model?.id}")

        if (model == null) {
            // 一個模型都沒下載：明確告訴呼叫方"語言/模型不可用"，並提示使用者去下載
            Notifications.notifyNoModel(this)
            runCatching { listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
            return
        }

        val langCode = langCodeFor(params.langHint, model)
        val newSession = CaptureSession(
            ctx = applicationContext,
            callback = listener,
            model = model,
            params = params,
            langCode = langCode,
        ) {
            synchronized(lock) { if (session === it) session = null }
            stopListeningNotification()
        }
        synchronized(lock) {
            session?.cancel()
            session = newSession
        }
        startListeningNotification(model)
        newSession.start()
    }

    override fun onStopListening(listener: Callback) {
        Log.i(TAG, "onStopListening")
        synchronized(lock) { session }?.requestStop()
    }

    override fun onCancel(listener: Callback) {
        Log.i(TAG, "onCancel")
        synchronized(lock) { session?.cancel(); session = null }
        stopListeningNotification()
    }

    /** API 33+：告知呼叫方本服務支援的語言（已安裝=本機就緒的模型語言） */
    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        val prefs = Prefs(this)
        val store = ModelStore(this)
        val ready = store.readyModels()
        if (ready.isEmpty()) {
            runCatching { supportCallback.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
            return
        }
        val installed = LinkedHashSet<String>()
        ready.forEach { m ->
            if (m.langs.contains("中")) installed.add("zh-CN")
            if (m.langs.contains("英")) installed.add("en-US")
        }
        if (installed.isEmpty()) installed.add("zh-CN")
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(installed.toList())
            .build()
        runCatching { supportCallback.onSupportResult(support) }
    }

    /** 單工作階段：同時只允許一個辨識，避免搶麥克風 */
    override fun getMaxConcurrentSessionsCount(): Int = 1

    override fun onDestroy() {
        synchronized(lock) { session?.cancel(); session = null }
        // F21: keep the recognizer loaded for the next utterance (KeeperService holds the process).
        stopListeningNotification()
        super.onDestroy()
        Log.i(TAG, "service destroyed")
    }

    // ------------------------------------------------------------------ 解析

    private fun parseParams(intent: Intent?, prefs: Prefs): RequestParams {
        val forced = prefs.language
        val asked = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        val hint = when {
            forced == "zh" || forced == "en" -> forced
            asked.isNullOrBlank() -> "auto"
            asked.lowercase().startsWith("zh") || asked.lowercase().startsWith("cmn") -> "zh"
            asked.lowercase().startsWith("en") -> "en"
            else -> "auto"
        }
        val silenceExtra = intent?.getIntExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1,
        ) ?: -1
        val possiblyComplete = intent?.getIntExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1,
        ) ?: -1
        val silence = listOf(silenceExtra, possiblyComplete).filter { it in 200..5000 }.minOrNull()
            ?: prefs.silenceMs
        return RequestParams(
            langHint = hint,
            partialsWanted = intent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) ?: true,
            preferOffline = intent?.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) ?: true,
            maxResults = intent?.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1) ?: 1,
            silenceMs = silence,
            maxUtteranceMs = prefs.maxUtteranceMs,
            noSpeechTimeoutMs = prefs.noSpeechTimeoutMs,
        )
    }

    private fun langCodeFor(hint: String, model: AsrModel): String = when (model.kind) {
        ModelKind.SENSE_VOICE -> when (hint) {
            "zh" -> "zh"
            "en" -> "en"
            else -> "auto"
        }
        else -> hint
    }

    // ------------------------------------------------------------------ 通知

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_listening_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * 辨識期間顯示狀態通知。
     * 優先前景服務；本 App 刻意不申請 FOREGROUND_SERVICE（保持只有 RECORD_AUDIO + INTERNET），
     * 在沒有該權限的裝置上自動改用一般通知。
     */
    private fun startListeningNotification(model: AsrModel) {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_listening_title))
            .setContentText(getString(R.string.notif_listening_text, model.name))
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        val foregroundOk = runCatching { startForeground(NOTIF_ID, n) }.isSuccess
        if (!foregroundOk) {
            Notifications.notifyListening(this, n)
        }
    }

    private fun stopListeningNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        Notifications.cancelListening(this)
    }

    companion object {
        private const val TAG = "AsrService"
        const val CHANNEL = "dsh_asr_listening"
        const val NOTIF_ID = 1001

        /** 元件的完整名稱，用於在系統設定裡選擇本服務 */
        fun componentName(ctx: Context): String = "${ctx.packageName}/${AsrRecognitionService::class.java.name}"
    }
}

/** 模型選擇策略（v0.4.1 修正優先順序，見 pick 註解） */
object ModelPicker {

    /**
     * 選模型。
     *
     * ## 修的是什麼（v0.4.1）
     * 舊邏輯**只要預設模型語言符合就直接傳回**，於是"使用者把離線模型設成預設" →
     * 串流偏好被完全跳過 → 辨識不即時（原作者回饋「感覺不夠即時」）。
     * 實測記錄佐證：`model=paraformer-zh-small-int8`（離線）而非串流 zipformer。
     *
     * ## 新優先順序
     *  ① 預設模型**本身就是串流** → 用它（尊重使用者明確選擇）
     *  ② 開啟了"串流優先" → 語言符合的串流模型優先（即時性優先）
     *  ③ 預設模型（語言符合）→ ④ 推薦 → 第一個
     */
    fun pick(prefs: Prefs, store: ModelStore, langHint: String): AsrModel? {
        val ready = store.readyModels()
        if (ready.isEmpty()) return null
        val preferred = prefs.defaultModel()?.takeIf { m -> ready.any { it.id == m.id } }

        // ① 預設模型就是串流 → 直接用
        if (preferred != null && preferred.streaming &&
            (langHint == "auto" || matches(preferred, langHint))
        ) return preferred

        // ② 開了串流優先 → 語言符合的串流模型優先（解決"不即時"）
        if (prefs.preferStreaming) {
            val pool = ready.filter { matches(it, langHint) }.ifEmpty { ready }
            pool.firstOrNull { it.streaming }?.let { return it }
        }

        // ③ 預設模型（語言符合）
        if (preferred != null && (langHint == "auto" || matches(preferred, langHint))) return preferred
        val langPool = ready.filter { matches(it, langHint) }.ifEmpty { ready }
        if (preferred != null && langPool.any { it.id == preferred.id }) return preferred
        return langPool.firstOrNull { it.recommended } ?: langPool.first()
    }

    fun matches(model: AsrModel, langHint: String): Boolean = when (langHint) {
        "zh" -> model.langs.contains("中")
        "en" -> model.langs.contains("英")
        else -> true
    }
}

/** 通知小工具（模型缺少提醒等） */
object Notifications {

    private const val CHANNEL_MODEL = "dsh_asr_model"

    fun notifyNoModel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_MODEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MODEL, ctx.getString(R.string.channel_model), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, ModelsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL_MODEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.notif_nomodel_title))
            .setContentText(ctx.getString(R.string.notif_nomodel_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(1002, n) }
    }

    fun notifyListening(ctx: Context, notification: Notification) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(AsrRecognitionService.NOTIF_ID, notification) }
    }

    fun cancelListening(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(AsrRecognitionService.NOTIF_ID) }
    }
}
