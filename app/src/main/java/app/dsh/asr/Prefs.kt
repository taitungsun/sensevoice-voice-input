package app.dsh.asr

import android.content.Context

/** App 設定（SharedPreferences） */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("dsh_asr", Context.MODE_PRIVATE)

    /** 預設模型 id（未設定時取目錄裡推薦的第一個） */
    var defaultModelId: String
        get() = sp.getString(K_MODEL, "") ?: ""
        set(value) = sp.edit().putString(K_MODEL, value).apply()

    /** auto / zh / en —— 辨識語言偏好，auto 時按呼叫方 Intent 的 EXTRA_LANGUAGE 推斷 */
    var language: String
        get() = sp.getString(K_LANG, "auto") ?: "auto"
        set(value) = sp.edit().putString(K_LANG, value).apply()

    /** 說話結束後靜音多久判定一句話結束 */
    var silenceMs: Int
        get() = sp.getInt(K_SILENCE, 1200)
        set(value) = sp.edit().putInt(K_SILENCE, value).apply()

    /** 單次工作階段最長時長 */
    var maxUtteranceMs: Int
        get() = sp.getInt(K_MAX_UTTER, 30_000)
        set(value) = sp.edit().putInt(K_MAX_UTTER, value).apply()

    /** 一直沒人說話時的等待上限（逾時傳回 ERROR_SPEECH_TIMEOUT） */
    var noSpeechTimeoutMs: Int
        get() = sp.getInt(K_NO_SPEECH, 8000)
        set(value) = sp.edit().putInt(K_NO_SPEECH, value).apply()

    /** 目前預設模型（目錄物件） */
    fun defaultModel(): AsrModel? =
        ModelCatalog.byId(defaultModelId) ?: ModelCatalog.MODELS.firstOrNull { it.recommended }

    companion object {
        private const val K_MODEL = "default_model"
        private const val K_LANG = "language"
        private const val K_SILENCE = "silence_ms"
        private const val K_MAX_UTTER = "max_utterance_ms"
        private const val K_NO_SPEECH = "no_speech_timeout_ms"
    }
}
