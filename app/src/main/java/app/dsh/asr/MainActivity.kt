package app.dsh.asr

import android.Manifest
import android.app.Activity
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

    /**
     * 狀態卡只講結論：「可以使用」或「還差哪幾步」。
     * 服務註冊、系統可見服務數這類細節留給「可用性檢查」。
     */
    private fun refreshStatus() {
        val prefs = Prefs(this)
        val store = ModelStore(this)
        val ready = store.readyModels()
        val def = prefs.defaultModel()

        val current = Settings.Secure.getString(contentResolver, "voice_recognition_service").orEmpty()
        val isDefault = current.startsWith(packageName)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val todo = buildList {
            if (ready.isEmpty()) add("到「辨識模型」下載模型")
            if (!isDefault) add("到系統設定選擇本 App 為語音輸入")
            if (!micOk) add("允許麥克風權限")
        }
        tvServiceState.text =
            if (todo.isEmpty()) "✓ 可以使用"
            else "尚未完成：\n" + todo.joinToString("\n") { "· $it" }
        tvServiceState.setTextColor(if (todo.isEmpty()) Ui.OK else Ui.WARN)

        val used = ModelCatalog.MODELS.sumOf { store.storedBytes(it) }
        tvModelState.text = buildString {
            append("模型：${def?.name ?: "-"}")
            append(if (def != null && def in ready) "（已就緒）\n" else "（未下載）\n")
            append("辨識語言：${langLabel(prefs.language)}\n")
            append("已佔用空間：${ModelCatalog.human(used)}")
        }
        tvModelState.setTextColor(Ui.TEXT_DIM)
    }

    private fun langLabel(code: String): String = when (code) {
        "zh" -> getString(R.string.lang_zh)
        "en" -> getString(R.string.lang_en)
        else -> getString(R.string.lang_auto)
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

}
