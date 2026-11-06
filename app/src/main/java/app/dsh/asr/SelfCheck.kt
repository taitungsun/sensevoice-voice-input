package app.dsh.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 可用性自檢（v0.3.0）。
 *
 * ## 為什麼需要
 * 主人問「我怎麼確認它能不能正常使用呢」—— 語音識別涉及**四層**，任何一層斷都表現為
 * "點了沒反應"，使用者無從判斷是哪一層的問題：
 *   ① 服務是否被系統註冊/可見
 *   ② 是否被設為系統預設識別服務（國產 ROM 常把設定頁藏起來，使用者設不了）
 *   ③ 模型是否已下載且能真正載入（檔案缺一個就載入失敗）
 *   ④ 完整鏈路：`SpeechRecognizer` → 本服務 → 引擎 → 回撥（**真跑一次**）
 *
 * 本類逐層檢測並給出**明確結論 + 下一步該怎麼辦**，把"玄學"變成可操作項。
 *
 * ## 第 ④ 項怎麼在沒有麥克風時也能驗
 * 用 `SpeechRecognizer` 真實繫結本服務併發起一次識別（不喂音訊），
 * 只要走到 `onReadyForSpeech` 就證明**繫結 + 服務 + 引擎初始化**整條鏈路通了；
 * 超時/報錯也能區分是哪一環（如 ERROR_CLIENT = 繫結失敗、ERROR_AUDIO = 錄音打不開）。
 */
object SelfCheck {

    private const val TAG = "SelfCheck"

    /** 單項結果 */
    data class Item(
        val title: String,
        val ok: Boolean,
        /** 補充說明（失敗時給"該怎麼辦"） */
        val detail: String = "",
        /** 無法判定（如需要使用者操作才能驗的項） */
        val warn: Boolean = false,
    )

    /** 完整檢測報告 */
    data class Report(val items: List<Item>) {
        /** 關鍵項（①②③）全過就算"可用"；④ 失敗會單獨提示 */
        val usable: Boolean get() = items.filter { !it.warn }.all { it.ok }
        val summary: String
            get() = when {
                items.all { it.ok } -> "一切正常，可以直接使用 ✓"
                items.any { !it.ok } -> "有問題，見下方逐項說明"
                else -> "大致可用，有部分提示"
            }
    }

    /**
     * 跑完整自檢。**阻塞**（第 ④ 項要等識別回撥），請在後臺執行緒呼叫。
     * @param onStage 進度回撥（主執行緒），讓介面能實時顯示"正在測哪一項"
     */
    fun run(ctx: Context, onStage: (String) -> Unit = {}): Report {
        val items = mutableListOf<Item>()

        // ① 服務是否被系統註冊且可見
        onStage("檢查服務註冊…")
        val mine = ComponentName(ctx, AsrRecognitionService::class.java)
        val declared = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SERVICES)
        }.getOrNull()?.services?.any { it.name == AsrRecognitionService::class.java.name } == true

        val all = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.queryIntentServices(Intent(ACTION_RECOGNITION_SERVICE), 0)
        }.getOrDefault(emptyList())
        val listed = all.any { it.serviceInfo.name == mine.className }

        items += Item(
            "服務已註冊", declared && listed,
            if (declared && listed) "系統列出 ${all.size} 個辨識服務，本 App 在清單中"
            else if (!declared) "清單裡沒有宣告 RecognitionService（App 損壞，請重新安裝）"
            else "系統未列出本 App（可能被 ROM 攔截，請試著重新開機）",
        )

        // ② 是否被設為系統預設（這項**決定其它 App 能否直接用**）
        onStage("檢查系統預設設定…")
        val current = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
        }.getOrNull().orEmpty()
        val isDefault = current.startsWith(ctx.packageName)
        items += Item(
            "已設為系統預設辨識服務", isDefault,
            if (isDefault) "其他 App 直接呼叫即可"
            else "目前是：${current.ifBlank { "未設定" }}。點下方「設為系統預設」一鍵設定；"
                + "若失敗，表示系統限制較嚴，可在呼叫端 App 裡手動指定本服務",
            warn = !isDefault,
        )

        // ③ 模型是否就緒且能載入
        onStage("檢查模型…")
        val store = ModelStore(ctx)
        val ready = store.readyModels()
        val prefs = Prefs(ctx)
        val target = prefs.defaultModel() ?: ready.firstOrNull()
        var modelOk = false
        var modelDetail = ""
        if (target == null) {
            modelDetail = "還沒有可用的模型，請先到「辨識模型」下載一個（建議先下載最小的）"
        } else {
            modelOk = runCatching {
                // 真正構建一次識別器 —— 檔案缺一個就會在這裡失敗
                if (target.streaming) {
                    EngineHolder.online(ctx, target)
                } else {
                    EngineHolder.offline(ctx, target, prefs.language)
                }
                true
            }.getOrElse {
                modelDetail = "模型載入失敗：${it.message}（可能下載不完整，請刪除後重新下載）"
                false
            }
            if (modelOk) modelDetail = "已載入「${target.name}」（${ModelCatalog.human(target.totalBytes)}）"
        }
        items += Item("模型可用", modelOk, modelDetail)

        // ④ 完整鏈路：真的用 SpeechRecognizer 調一次（能到 readyForSpeech 就算通）
        onStage("測試完整流程（綁定 → 引擎 → 回呼）…")
        val chain = testChain(ctx)
        items += chain

        onStage("完成")
        return Report(items)
    }

    /**
     * 用 `SpeechRecognizer` 真實繫結本服務，發起一次識別。
     * 只要回撥走到 `onReadyForSpeech`，就說明**繫結 + 服務 + 引擎初始化**全通。
     * 不給音訊，幾秒後主動取消 —— 目的是驗證鏈路，不是驗證識別內容。
     */
    private fun testChain(ctx: Context): Item {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            return Item("完整流程", false, "系統沒有可用的辨識服務（先完成上面幾項）")
        }
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var reachedReady = false
        var errCode = 0
        var errMsg = ""

        handler.post {
            runCatching {
                val r = SpeechRecognizer.createSpeechRecognizer(
                    ctx, ComponentName(ctx, AsrRecognitionService::class.java)
                )
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        reachedReady = true
                        latch.countDown()
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onResults(results: Bundle?) { latch.countDown() }
                    override fun onError(error: Int) {
                        errCode = error
                        latch.countDown()
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                r.startListening(intent)
                // 6 秒後無論如何都收工（驗證鏈路即可，不等識別內容）
                handler.postDelayed({
                    runCatching { r.cancel() }
                    runCatching { r.destroy() }
                    latch.countDown()
                }, 6_000)
            }.onFailure {
                errMsg = it.message.orEmpty()
                latch.countDown()
            }
        }

        latch.await(9, TimeUnit.SECONDS)

        return when {
            reachedReady -> Item(
                "完整流程", true,
                "已成功綁定本服務並開始錄音（表示服務與引擎都正常）",
            )
            errCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Item(
                "完整流程", false, "權限不足 —— 請確認已授予麥克風權限",
            )
            errCode == SpeechRecognizer.ERROR_CLIENT -> Item(
                "完整流程", false, "綁定被拒（ERROR_CLIENT）—— 可能是系統限制，請試著重新開機",
            )
            errCode == SpeechRecognizer.ERROR_AUDIO -> Item(
                "完整流程", false, "無法開啟錄音（ERROR_AUDIO）—— 請檢查是否有其他 App 正在使用麥克風",
            )
            errCode != 0 -> Item("完整流程", false, "辨識傳回錯誤碼 $errCode")
            errMsg.isNotEmpty() -> Item("完整流程", false, errMsg)
            else -> Item("完整流程", false, "等待逾時，未收到任何回呼（服務可能沒有回應）")
        }
    }

    const val ACTION_RECOGNITION_SERVICE = "android.speech.RecognitionService"

    /**
     * 嘗試把本應用設為系統預設識別服務。
     *
     * 普通應用**沒有** `WRITE_SECURE_SETTINGS` 許可權，正常寫不了這個鍵。
     * 三條路徑，按成功率排序：
     *  ① 直接寫（已 root / 有許可權時可用）
     *  ② 用 root 寫（`su -c settings put`）
     *  ③ 都不行 → 返回 false，由介面引導使用者去系統設定（或改用呼叫方指定服務）
     *
     * @return 是否設定成功
     */
    fun trySetAsDefault(ctx: Context): Boolean {
        val spec = "${ctx.packageName}/${AsrRecognitionService::class.java.name}"
        // ① 直接寫
        val direct = runCatching {
            Settings.Secure.putString(ctx.contentResolver, "voice_recognition_service", spec)
        }.isSuccess && isDefaultNow(ctx)
        if (direct) return true

        // ② root
        val rooted = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure voice_recognition_service $spec"))
            p.waitFor(4, TimeUnit.SECONDS)
            p.exitValue() == 0
        }.getOrDefault(false) && isDefaultNow(ctx)
        if (rooted) return true

        Log.w(TAG, "cannot set default service without WRITE_SECURE_SETTINGS or root")
        return false
    }

    private fun isDefaultNow(ctx: Context): Boolean = runCatching {
        Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
            .orEmpty().startsWith(ctx.packageName)
    }.getOrDefault(false)

    /**
     * 開啟系統「語音輸入」設定頁 —— **多路徑兜底**。
     *
     * 主人反饋「有些國產系統設定入口很亂，都找不到」。這裡按可靠性依次嘗試：
     *  ① 標準 `ACTION_VOICE_INPUT_SETTINGS`
     *  ② 直接跳到「語言和輸入法」設定
     *  ③ 直接跳到本應用的系統設定詳情頁（那裡至少能看到許可權）
     * 每步都驗證"是否真的切走了"，全失敗才提示手動路徑。
     */
    fun openVoiceInputSettings(ctx: Context): Boolean {
        // ⚠️ 順序很重要（v0.3.1 修正）：
        // 參考 Sayboard 的做法 —— 它用的是 **INPUT_METHOD_SETTINGS（語言和輸入法）**，
        // 而不是 VOICE_INPUT_SETTINGS。原因：後者在**國產 ROM 上常被重定向到數字助理頁**
        // （主人真機實測："開啟系統設定開啟的選項是數字助理應用"），根本設不了識別服務。
        // 而「語言和輸入法」頁幾乎每個 ROM 都有，且裡面有語音輸入子項。
        val candidates = listOf(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),   // 語言和輸入法（首選）
            Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),    // 標準語音輸入頁（部分 ROM 可用）
            Intent(Settings.ACTION_SETTINGS),                // 系統設定主頁兜底
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${ctx.packageName}")),
        )
        for (it in candidates) {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { ctx.startActivity(it); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
