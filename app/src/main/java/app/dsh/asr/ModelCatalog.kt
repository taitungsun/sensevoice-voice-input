package app.dsh.asr

/**
 * 模型檔案：檔名 + hf-mirror 上的**精確位元組數**（實測 Content-Length）。
 * 下載完成後按此驗證，防止半截/被篡改的檔案進入推論。
 */
data class ModelFile(val name: String, val bytes: Long)

enum class ModelKind {
    /** SenseVoice：非串流、多語言、帶標點 */
    SENSE_VOICE,

    /** Paraformer：非串流、中文、帶時間戳記 */
    PARAFORMER,

    /** 串流 zipformer transducer：邊說邊出字 */
    STREAMING_TRANSDUCER,
}

data class AsrModel(
    val id: String,
    val name: String,
    val langs: String,
    val note: String,
    val repo: String,
    val kind: ModelKind,
    val files: List<ModelFile>,
    val recommended: Boolean = false,
) {
    val streaming: Boolean get() = kind == ModelKind.STREAMING_TRANSDUCER
    val totalBytes: Long get() = files.fold(0L) { acc, f -> acc + f.bytes }
}

object ModelCatalog {

    /** 中國可直連的鏡像站（hf-mirror.com）。下載 URL 形如 <base>/<repo>/resolve/main/<file> */
    const val MIRROR = "https://hf-mirror.com"

    /** HuggingFace 官方來源（境外網路環境下更快） */
    const val OFFICIAL = "https://huggingface.co"

    /**
     * 下載來源（v0.5.0）。
     *
     * 原作者要求「英文環境下切換到境外來源下載」——
     * 中國鏡像站對境外使用者反而慢/不可達，官方來源則相反。
     * 所以**按介面語言自動選**，同時允許使用者在設定裡手動覆蓋。
     */
    enum class Source(val base: String) {
        /** 中國鏡像站 hf-mirror.com */
        MIRROR(ModelCatalog.MIRROR),

        /** HuggingFace 官方 */
        OFFICIAL(ModelCatalog.OFFICIAL),
    }

    /**
     * 按語言代碼決定預設來源：中文 → 中國鏡像站，其他 → 官方。
     *
     * 判斷依據用語言標籤字首（`zh`），與介面語言切換保持一致：
     * 使用者把 App 語言設成中文，多半在中國網路；設成英文，多半在境外。
     */
    fun defaultSource(langTag: String?): Source {
        val t = langTag?.lowercase().orEmpty()
        // Fork: only Simplified-Chinese locales use the mainland mirror;
        // zh-TW / zh-HK / zh-MO / zh-Hant download from HuggingFace directly.
        val traditional = Regex("^zh[-_](tw|hk|mo|hant)").containsMatchIn(t)
        return if (t.startsWith("zh") && !traditional) Source.MIRROR else Source.OFFICIAL
    }

    /** 解析來源基底網址：空/無效值退回官方（英文預設），保證永遠有可用 URL */
    fun sourceFrom(name: String?): Source =
        Source.entries.firstOrNull { it.name == name } ?: Source.OFFICIAL

    /**
     * 建立下載 URL。
     * @param source 下載來源（由 Prefs 提供，見 `Prefs.modelSource`）
     */
    fun url(model: AsrModel, file: ModelFile, source: Source = Source.MIRROR): String =
        "${source.base}/${model.repo}/resolve/main/${file.name}"

    val MODELS: List<AsrModel> = listOf(
        AsrModel(
            id = "sensevoice-small-int8",
            name = "SenseVoice Small",
            langs = "中 · 英 · 日 · 韓 · 粵",
            note = "官方推薦：辨識品質最好，自動加標點，支援中英混說",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            kind = ModelKind.SENSE_VOICE,
            files = listOf(
                ModelFile("model.int8.onnx", 239_233_841L),
                ModelFile("tokens.txt", 315_894L),
            ),
            recommended = true,
        ),
        // Fork: the 2025-09-09 sherpa-onnx export is SenseVoice Small fine-tuned
        // on Cantonese (ASLP-lab WSYue-ASR, WenetSpeech-Yue).
        AsrModel(
            id = "sensevoice-small-yue-int8",
            name = "SenseVoice Small (粵語微調 WSYue)",
            langs = "中 · 英 · 日 · 韓 · 粵",
            note = "WenetSpeech-Yue 粵語微調版；華語也可使用",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            kind = ModelKind.SENSE_VOICE,
            files = listOf(
                ModelFile("model.int8.onnx", 237_115_547L),
                ModelFile("tokens.txt", 315_894L),
            ),
        ),
        AsrModel(
            id = "paraformer-zh-int8",
            name = "Paraformer 中文（大）",
            langs = "中",
            note = "純中文，長句穩定；檔案較大",
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09",
            kind = ModelKind.PARAFORMER,
            files = listOf(
                ModelFile("model.int8.onnx", 227_330_205L),
                ModelFile("tokens.txt", 75_354L),
            ),
        ),
        AsrModel(
            id = "paraformer-zh-small-int8",
            name = "Paraformer 中文（小）",
            langs = "中",
            note = "輕量中文（82MB），下載快、佔用資源少",
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09",
            kind = ModelKind.PARAFORMER,
            files = listOf(
                ModelFile("model.int8.onnx", 81_828_675L),
                ModelFile("tokens.txt", 75_352L),
            ),
        ),
        AsrModel(
            id = "zipformer-bilingual-zh-en-int8",
            name = "串流 Zipformer 中英雙語",
            langs = "中 · 英",
            note = "邊說邊出字（串流），中英混說",
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            kind = ModelKind.STREAMING_TRANSDUCER,
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 181_895_032L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 13_876_452L),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 3_228_404L),
                ModelFile("tokens.txt", 56_317L),
            ),
        ),
        AsrModel(
            id = "zipformer-zh-14m-int8",
            name = "串流 Zipformer 中文（輕量）",
            langs = "中",
            note = "25MB 串流中文，下載很快；適合先安裝來測試流程",
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            kind = ModelKind.STREAMING_TRANSDUCER,
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 21_621_684L),
                ModelFile("decoder-epoch-99-avg-1.int8.onnx", 1_888_682L),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 1_795_562L),
                ModelFile("tokens.txt", 48_697L),
            ),
        ),
        AsrModel(
            id = "zipformer-en-20m-int8",
            name = "串流 Zipformer 英文（輕量）",
            langs = "英",
            note = "44MB 串流英文，純英文情境更準",
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            kind = ModelKind.STREAMING_TRANSDUCER,
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 42_845_182L),
                ModelFile("decoder-epoch-99-avg-1.int8.onnx", 539_499L),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 259_572L),
                ModelFile("tokens.txt", 5_048L),
            ),
        ),
    )

    fun byId(id: String?): AsrModel? = MODELS.firstOrNull { it.id == id }

    /** 大小轉成易讀格式：239.5 MB / 25.4 MB */
    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
