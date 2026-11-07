package app.dsh.asr

/**
 * 模型檔案：檔名 + HuggingFace 上的**精確位元組數**（實測 Content-Length）。
 * 下載完成後按此驗證，防止半截/被篡改的檔案進入推論。
 */
data class ModelFile(val name: String, val bytes: Long)

/** SenseVoice 模型（離線整句、多語言、自動加標點）。 */
data class AsrModel(
    val id: String,
    val name: String,
    val langs: String,
    val note: String,
    val repo: String,
    val files: List<ModelFile>,
    val recommended: Boolean = false,
) {
    val totalBytes: Long get() = files.fold(0L) { acc, f -> acc + f.bytes }
}

object ModelCatalog {

    /** 模型一律從 HuggingFace 官方下載。URL 形如 <base>/<repo>/resolve/main/<file> */
    const val BASE = "https://huggingface.co"

    fun url(model: AsrModel, file: ModelFile): String =
        "$BASE/${model.repo}/resolve/main/${file.name}"

    val MODELS: List<AsrModel> = listOf(
        AsrModel(
            id = "sensevoice-small-int8",
            name = "SenseVoice Small",
            langs = "中 · 英 · 日 · 韓 · 粵",
            note = "官方推薦：辨識品質最好，自動加標點，支援中英混說",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            files = listOf(
                ModelFile("model.int8.onnx", 239_233_841L),
                ModelFile("tokens.txt", 315_894L),
            ),
            recommended = true,
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
