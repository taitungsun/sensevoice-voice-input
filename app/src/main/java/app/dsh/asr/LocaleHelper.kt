package app.dsh.asr

import android.content.Context

/**
 * 介面語言工具（v0.5.1）。
 *
 * ## 為什麼不直接用 Locale.getDefault()
 * Android 上 `Locale.getDefault()` 是 JVM 快取的靜態值：系統語言變更、
 * 甚至 `Resources` 設定更新後，它都可能還是舊值。實測把模擬器切到 zh-CN 後，
 * 用 `Locale.getDefault()` 仍解析出 en-US，導致下載來源判定錯誤。
 *
 * `resources.configuration.locales` 才是**目前 App 實際生效**的語言
 * （系統語言變更或 attachBaseContext 包裹後都會同步更新），故統一從這裡讀。
 */
object LocaleHelper {

    /**
     * 目前生效語言的 BCP-47 標籤（如 `zh-CN` / `en-US`）。
     *
     * 相容兩種讀法：API 24+ 用 `configuration.locales`（有序列表，取第一個），
     * 更舊的版本改用已廢棄的 `configuration.locale`。
     */
    fun langTag(context: Context): String {
        val cfg = context.resources.configuration
        val locale = if (android.os.Build.VERSION.SDK_INT >= 24) {
            cfg.locales.takeIf { !it.isEmpty }?.get(0)
        } else {
            @Suppress("DEPRECATION")
            cfg.locale
        }
        return locale?.toLanguageTag().orEmpty()
    }
}
