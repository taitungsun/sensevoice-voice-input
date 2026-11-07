package app.dsh.asr

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView

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
            append("語言偏好：${prefs.language}")
        }
        tvModelState.setTextColor(Ui.statusColor(ready.isNotEmpty(), warn = ready.isEmpty()))
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

    override fun onProgress(modelId: String, done: Long, total: Long) = Unit

    override fun onFinished(modelId: String, ok: Boolean, message: String?) {
        refreshStatus()
    }

    companion object {
        private const val RecognitionServiceAction = "android.speech.RecognitionService"
    }
}
