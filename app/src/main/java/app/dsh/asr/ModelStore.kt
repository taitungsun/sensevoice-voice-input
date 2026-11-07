package app.dsh.asr

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** 模型在本機磁碟上的狀態 */
enum class ModelState { MISSING, PARTIAL, READY }

class DownloadCancelled : Exception("download cancelled")

/**
 * 模型儲存庫：負責儲存路徑、狀態判斷、下載（斷點續傳 + 大小驗證）、刪除。
 *
 * 儲存位置：`<外部私有目錄>/models/<modelId>/<file>`，外部私有目錄不可用時退回內部 filesDir。
 * 說明：外部私有目錄無需任何儲存權限（App 專屬目錄），且方便使用者用檔案管理器檢視/清理。
 */
class ModelStore(context: Context) {

    private val appCtx = context.applicationContext

    /**
     * 目前生效的下載來源（v0.5.0）。
     * 按介面語言推斷（中文→hf-mirror，其他→HuggingFace 官方），設定頁可手動覆蓋。
     *
     * ⚠️ 語言必須從 `resources.configuration` 讀，**不能用 `Locale.getDefault()`**：
     * 後者是 JVM 快取的靜態值，Android 在系統語言變更（或 App 內切換語言）後
     * 不會更新它 —— 實測把系統切到中文後仍解析成官方來源。
     */
    private fun source(): ModelCatalog.Source =
        Prefs(appCtx).modelSource(LocaleHelper.langTag(appCtx))

    val root: File = run {
        val base = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        File(base, "models").also { it.mkdirs() }
    }

    fun dirOf(model: AsrModel): File = File(root, model.id)

    fun fileOf(model: AsrModel, file: ModelFile): File = File(dirOf(model), file.name)

    private fun partOf(model: AsrModel, file: ModelFile): File = File(dirOf(model), file.name + ".part")

    /** 單個檔案是否完整（存在且位元組數完全符合目錄中登記的實測大小） */
    fun fileReady(model: AsrModel, file: ModelFile): Boolean {
        val f = fileOf(model, file)
        return f.isFile && f.length() == file.bytes
    }

    /** 已完整寫入的檔案集合 */
    fun readyFiles(model: AsrModel): List<ModelFile> = model.files.filter { fileReady(model, it) }

    fun state(model: AsrModel): ModelState {
        val ready = readyFiles(model).size
        if (ready == model.files.size) return ModelState.READY
        val hasPartial = model.files.any { partOf(model, it).isFile && partOf(model, it).length() > 0 }
        return if (ready > 0 || hasPartial) ModelState.PARTIAL else ModelState.MISSING
    }

    /** 已佔用/已下載位元組數（含 .part 斷點） */
    fun storedBytes(model: AsrModel): Long = model.files.sumOf { f ->
        val done = if (fileReady(model, f)) f.bytes else 0L
        done + (partOf(model, f).takeIf { it.isFile }?.length() ?: 0L)
    }

    fun delete(model: AsrModel) {
        val dir = dirOf(model)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /** 目前已完整下載的模型 */
    fun readyModels(): List<AsrModel> = ModelCatalog.MODELS.filter { state(it) == ModelState.READY }

    /**
     * 下載整個模型（多個檔案依序下載）。
     *
     * @param onProgress (已完成位元組, 總位元組, 目前檔案說明)
     * @param cancel 外部取消旗標（UI 上的「取消」）
     * @return true = 全部檔案驗證通過
     */
    fun download(
        model: AsrModel,
        cancel: AtomicBoolean,
        onProgress: (Long, Long, String) -> Unit,
    ): Boolean {
        var doneBase = 0L
        val total = model.totalBytes
        for (file in model.files) {
            if (cancel.get()) throw DownloadCancelled()
            val ok = downloadFile(model, file, cancel) { inFile ->
                onProgress(doneBase + inFile, total, file.name)
            }
            if (!ok) return false
            doneBase += file.bytes
        }
        return true
    }

    /**
     * 單檔案下載：支援 HTTP Range 斷點續傳。
     * `.part` 累積 → 驗證長度 == 目錄登記位元組數 → 原子改名成正式檔案。
     */
    private fun downloadFile(
        model: AsrModel,
        file: ModelFile,
        cancel: AtomicBoolean,
        onBytes: (Long) -> Unit,
    ): Boolean {
        val dest = fileOf(model, file)
        if (fileReady(model, file)) {
            onBytes(file.bytes)
            return true
        }
        dirOf(model).mkdirs()
        val part = partOf(model, file)

        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            if (cancel.get()) throw DownloadCancelled()

            var existing = if (part.isFile) part.length() else 0L
            if (existing > file.bytes) {           // 本機比遠端大：檔案有誤，從頭下載
                part.delete()
                existing = 0L
            }
            if (existing == file.bytes) {
                if (part.renameTo(dest)) { onBytes(file.bytes); return true }
                part.delete()
                existing = 0L
            }

            var conn: HttpURLConnection? = null
            try {
                val url = URL(ModelCatalog.url(model, file, source()))
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "dsh-asr-service/0.1 (Android)")
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    Log.w(TAG, "HTTP $code for ${file.name} (attempt $attempt)")
                    attempt--
                    if (attempt >= MAX_ATTEMPTS) return false
                    sleepBackoff(attempt)
                    continue
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL

                RandomAccessFile(part, "rw").use { raf ->
                    if (!append) raf.setLength(0)
                    raf.seek(if (append) existing else 0L)
                    var written = if (append) existing else 0L
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancel.get()) throw DownloadCancelled()
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            written += n
                            onBytes(written)
                        }
                    }
                }
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download ${file.name} attempt $attempt failed: ${e.message}")
                if (attempt >= MAX_ATTEMPTS) return false
                sleepBackoff(attempt)
                continue
            } finally {
                conn?.disconnect()
            }

            // 下載完成 → 嚴格驗證位元組數
            val got = part.length()
            if (got == file.bytes) {
                if (!part.renameTo(dest)) {
                    // 改名失敗（極少見）：直接把內容搬過去
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                Log.i(TAG, "downloaded ok: ${model.id}/${file.name} = $got bytes")
                return true
            }
            Log.w(TAG, "size mismatch ${file.name}: got=$got expected=${file.bytes}, retry")
            part.delete()
            sleepBackoff(attempt)
        }
        return false
    }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep(800L * attempt)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "ModelStore"
        private const val MAX_ATTEMPTS = 5
    }
}
