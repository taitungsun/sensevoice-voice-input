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

    /** 是否優先使用串流模型（有串流模型可用時） */
    var preferStreaming: Boolean
        get() = sp.getBoolean(K_STREAMING, true)
        set(value) = sp.edit().putBoolean(K_STREAMING, value).apply()

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

    /**
     * 模型下載來源（v0.5.0）。
     *
     * 原作者要求「英文環境下切換到境外來源下載」：中國鏡像站對境外使用者慢甚至不可達，
     * HuggingFace 官方來源則相反。預設值**按介面語言推斷**（中文→鏡像站，其他→官方），
     * 使用者可在設定頁手動覆蓋 —— 覆蓋後以使用者選擇為準，不再跟隨語言。
     *
     * 空字串 = 未手動設定 → 跟隨語言。
     */
    var modelSourceOverride: String
        get() = sp.getString(K_SOURCE, "") ?: ""
        set(value) = sp.edit().putString(K_SOURCE, value).apply()

    /**
     * 解析出**目前生效**的下載來源。
     * @param langTag 目前介面語言標籤（如 `zh-CN` / `en-US`）
     */
    fun modelSource(langTag: String?): ModelCatalog.Source {
        val override = modelSourceOverride
        return if (override.isBlank()) ModelCatalog.defaultSource(langTag)
        else ModelCatalog.sourceFrom(override)
    }

    /** 目前預設模型（目錄物件） */
    fun defaultModel(): AsrModel? =
        ModelCatalog.byId(defaultModelId) ?: ModelCatalog.MODELS.firstOrNull { it.recommended }

    companion object {
        private const val K_MODEL = "default_model"
        private const val K_LANG = "language"
        private const val K_STREAMING = "prefer_streaming"
        private const val K_SILENCE = "silence_ms"
        private const val K_MAX_UTTER = "max_utterance_ms"
        private const val K_NO_SPEECH = "no_speech_timeout_ms"
        private const val K_SOURCE = "model_source"
    }
}
