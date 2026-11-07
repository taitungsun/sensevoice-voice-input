package app.dsh.asr

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.TextView
import java.io.File

class MainActivity : Activity(), DownloadBus.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvServiceState: TextView
    private lateinit var tvModelState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceState = findViewById(R.id.tvServiceState)
        tvModelState = findViewById(R.id.tvModelState)

        // 可用性檢查（v0.3.0）：原作者問「怎麼確認能不能正常使用」——
        // 一鍵跑四層檢查（服務註冊 / 系統預設 / 模型載入 / 完整流程），逐項給結論
        findViewById<android.view.View>(R.id.rowRunCheck).setOnClickListener { runCheck() }
        // 「設為系統預設」（v0.4.0）：三級嘗試
        //  ① Shizuku（原作者要求：嘗試申請 SHZ 權限）—— 有裝就用它，成功率最高
        //  ② root / 直接寫
        //  ③ 都不行 → 明確告知改用「呼叫方指定服務」
        findViewById<android.view.View>(R.id.rowSetDefault).setOnClickListener {
            setDefaultFlow()
        }
        findViewById<android.view.View>(R.id.rowOpenSettings).setOnClickListener {
            // 多路徑備援：中國廠商 ROM 的設定入口很亂，逐條嘗試直到真的跳走
            if (!SelfCheck.openVoiceInputSettings(this)) {
                Ui.toast(this, getString(R.string.check_open_fail))
            }
        }

        findViewById<android.view.View>(R.id.cardModels).setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.cardSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        probeShizuku()
        ensureMicPermission()
    }

    override fun onResume() {
        super.onResume()
        DownloadBus.register(this)
        refreshStatus()
    }

    override fun onPause() {
        DownloadBus.unregister(this)
        super.onPause()
    }

    // ------------------------------------------------------------------ 狀態

    private fun refreshStatus() {
        val mine = ComponentName(this, AsrRecognitionService::class.java)

        val declared = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SERVICES)
        }.getOrNull()?.services?.any { it.name == AsrRecognitionService::class.java.name } == true

        val allServices = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(Intent(RecognitionServiceAction), 0)
        }.getOrDefault(emptyList())
        val mineIndex = allServices.indexOfFirst { it.serviceInfo.name == mine.className }

        val current = Settings.Secure.getString(contentResolver, "voice_recognition_service").orEmpty()
        val isDefault = current.startsWith(packageName)

        tvServiceState.text = buildString {
            append(if (declared) "服務註冊：已宣告 RecognitionService ✓\n" else "服務註冊：未在清單中找到 ✗\n")
            append("系統可見辨識服務：${allServices.size} 個")
            if (mineIndex >= 0) append("（本 App 在清單中 ✓）") else append("（本 App 未被系統列出 ✗）")
            append("\n")
            append(
                if (isDefault) "系統預設辨識服務：本 App ✓（其他 App 直接呼叫即可）"
                else "系統預設辨識服務：${if (current.isBlank()) "未設定" else current}"
            )
        }
        tvServiceState.setTextColor(Ui.statusColor(declared && mineIndex >= 0, warn = declared))

        val prefs = Prefs(this)
        val store = ModelStore(this)
        val ready = store.readyModels()
        val def = prefs.defaultModel()
        val used = ModelCatalog.MODELS.sumOf { store.storedBytes(it) }
        tvModelState.text = buildString {
            append("預設模型：${def?.name ?: "-"}（${def?.let { ModelCatalog.human(it.totalBytes) } ?: "-"}）\n")
            append("已就緒模型：${if (ready.isEmpty()) "無（先去模型管理下載）" else ready.joinToString("、") { it.name }}\n")
            append("已佔用空間：${ModelCatalog.human(used)}\n")
            append("語言偏好：${prefs.language} · 串流優先：${if (prefs.preferStreaming) "開" else "關"}")
        }
        tvModelState.setTextColor(Ui.statusColor(ready.isNotEmpty(), warn = ready.isEmpty()))
    }

    private fun openVoiceInputSettings() {
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isSuccess
        if (!ok) Ui.toast(this, "系統沒有「語音輸入」設定頁，請到 系統設定 → 語言與輸入設定 裡找")
    }

    // ------------------------------------------------------------------ 自我檢查：走 SpeechRecognizer 全流程


    // ------------------------------------------------------------------ 自我檢查：WAV 解碼（無麥克風也能驗）


    // ------------------------------------------------------------------ 設為系統預設

    /** Shizuku 可用性探測（onCreate 時記一次，供 requestPermission 用） */
    private fun probeShizuku() {
        ShizukuHelper.markInstalled(ShizukuHelper.isInstalled(this))
    }

    /**
     * 設為系統預設辨識服務：Shizuku 優先 → root/直接寫 → 明確失敗提示。
     */
    private fun setDefaultFlow() {
        val spec = "${packageName}/${AsrRecognitionService::class.java.name}"
        val tv = findViewById<TextView>(R.id.tvCheckResult)
        tv.visibility = android.view.View.VISIBLE
        tv.setTextColor(Ui.TEXT_DIM)

        when {
            // ① Shizuku 已授權 → 直接執行
            ShizukuHelper.isAuthorized() -> {
                tv.text = getString(R.string.set_via_shizuku)
                Thread({
                    val ok = ShizukuHelper.setDefaultViaShizuku(this, spec)
                    runOnUiThread {
                        tv.text = getString(if (ok) R.string.check_set_ok else R.string.set_shizuku_failed)
                        tv.setTextColor(if (ok) Ui.OK else Ui.ERR)
                        refreshStatus()
                    }
                }, "shizuku-set").apply { isDaemon = true; start() }
            }
            // ② 裝了 Shizuku 但沒授權 → 申請權限，成功後自動繼續
            ShizukuHelper.isInstalled(this) -> {
                tv.text = getString(R.string.set_requesting_shizuku)
                ShizukuHelper.requestPermission { granted ->
                    runOnUiThread {
                        if (granted) {
                            setDefaultFlow()   // 授權成功 → 重走流程（這次會走 ①）
                        } else {
                            tv.text = getString(R.string.set_shizuku_denied)
                            tv.setTextColor(Ui.WARN)
                            fallbackSetDefault(spec, tv)
                        }
                    }
                }
            }
            // ③ 沒裝 Shizuku → root/直接寫，再不行給指引
            else -> {
                tv.text = getString(R.string.set_no_shizuku)
                Thread({
                    val ok = SelfCheck.trySetAsDefault(this)
                    runOnUiThread {
                        if (ok) {
                            tv.text = getString(R.string.check_set_ok)
                            tv.setTextColor(Ui.OK)
                        } else {
                            tv.text = getString(R.string.set_all_failed)
                            tv.setTextColor(Ui.ERR)
                        }
                        refreshStatus()
                    }
                }, "set-default").apply { isDaemon = true; start() }
            }
        }
    }

    /** Shizuku 被拒後的備援：root / 直接寫 */
    private fun fallbackSetDefault(spec: String, tv: TextView) {
        Thread({
            val ok = SelfCheck.trySetAsDefault(this)
            runOnUiThread {
                if (ok) { tv.text = getString(R.string.check_set_ok); tv.setTextColor(Ui.OK) }
                refreshStatus()
            }
        }, "fallback-set").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ 可用性檢查

    /**
     * 一鍵檢查：背景跑（第 ④ 項要等辨識回呼），結果回主執行緒逐項顯示。
     * 每項都帶「該怎麼辦」，把摸不著頭緒的問題變成可操作的步驟。
     */
    private fun runCheck() {
        val tv = findViewById<TextView>(R.id.tvCheckResult)
        tv.visibility = android.view.View.VISIBLE
        tv.text = getString(R.string.check_running)
        tv.setTextColor(Ui.TEXT_DIM)

        Thread({
            val report = SelfCheck.run(this) { stage ->
                runOnUiThread { tv.text = stage }
            }
            runOnUiThread {
                tv.text = buildString {
                    append(report.summary).append("\n\n")
                    report.items.forEach { item ->
                        append(if (item.ok) "✓ " else if (item.warn) "! " else "✗ ")
                        append(item.title)
                        if (item.detail.isNotEmpty()) append("：").append(item.detail)
                        append("\n")
                    }
                }
                tv.setTextColor(if (report.usable) Ui.OK else Ui.ERR)
                refreshStatus()
            }
        }, "self-check").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ 工具

    private fun ensureMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            android.widget.Toast.makeText(this, "麥克風權限被拒絕：辨識服務將無法運作",
                android.widget.Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }


    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        13 -> "ERROR_LANGUAGE_UNAVAILABLE"
        else -> "code $code"
    }


    override fun onProgress(modelId: String, done: Long, total: Long) = Unit

    override fun onFinished(modelId: String, ok: Boolean, message: String?) {
        refreshStatus()
    }

    companion object {
        private const val RecognitionServiceAction = "android.speech.RecognitionService"
    }
}
