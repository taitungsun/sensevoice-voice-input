package app.dsh.asr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * 辨識設定。
 * 同時作為系統「語音輸入 → 辨識服務 → 設定」的入口（manifest 裡 settingsActivity 指向本頁）。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)
        store = ModelStore(this)

        buildLanguage()
        buildEndpoints()
        buildServiceInfo()

        findViewById<TextView>(R.id.btnOpenModels).setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        buildServiceInfo()
    }

    // ------------------------------------------------------------------ 語言

    private fun buildLanguage() {
        val rg = findViewById<RadioGroup>(R.id.rgLang)
        when (prefs.language) {
            "zh" -> rg.check(R.id.rbZh)
            "en" -> rg.check(R.id.rbEn)
            else -> rg.check(R.id.rbAuto)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            prefs.language = when (checkedId) {
                R.id.rbZh -> "zh"
                R.id.rbEn -> "en"
                else -> "auto"
            }
            buildServiceInfo()
        }
    }

    // ------------------------------------------------------------------ 錄音時間

    private fun buildEndpoints() {
        val tvSilence = findViewById<TextView>(R.id.tvSilence)
        val sbSilence = findViewById<SeekBar>(R.id.sbSilence)
        sbSilence.max = 26
        sbSilence.progress = ((prefs.silenceMs - 400) / 100).coerceIn(0, 26)
        fun showSilence() {
            tvSilence.text = getString(R.string.settings_silence, String.format(Locale.US, "%.1f", prefs.silenceMs / 1000f))
        }
        showSilence()
        sbSilence.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.silenceMs = 400 + progress * 100
                showSilence()
            }
        })

        val tvMaxLen = findViewById<TextView>(R.id.tvMaxLen)
        val sbMaxLen = findViewById<SeekBar>(R.id.sbMaxLen)
        sbMaxLen.max = 11
        sbMaxLen.progress = ((prefs.maxUtteranceMs - 10_000) / 5_000).coerceIn(0, 11)
        fun showMaxLen() {
            tvMaxLen.text = getString(R.string.settings_maxlen, (prefs.maxUtteranceMs / 1000).toString())
        }
        showMaxLen()
        sbMaxLen.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.maxUtteranceMs = 10_000 + progress * 5_000
                showMaxLen()
            }
        })

        val tvNoSpeech = findViewById<TextView>(R.id.tvNoSpeech)
        val sbNoSpeech = findViewById<SeekBar>(R.id.sbNoSpeech)
        sbNoSpeech.max = 17
        sbNoSpeech.progress = (prefs.noSpeechTimeoutMs / 1000 - 3).coerceIn(0, 17)
        fun showNoSpeech() {
            tvNoSpeech.text = getString(R.string.settings_nospeech, (prefs.noSpeechTimeoutMs / 1000).toString())
        }
        showNoSpeech()
        sbNoSpeech.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.noSpeechTimeoutMs = (3 + progress) * 1000
                showNoSpeech()
            }
        })
    }

    // ------------------------------------------------------------------ 服務資訊

    private fun buildServiceInfo() {
        val ready = store.readyModels()
        val used = ModelCatalog.MODELS.sumOf { store.storedBytes(it) }
        findViewById<TextView>(R.id.tvServiceInfo).text = buildString {
            append(getString(R.string.settings_engine))
            append('\n')
            append(getString(R.string.settings_component, AsrRecognitionService.componentName(this@SettingsActivity)))
            append('\n')
            append(getString(R.string.settings_default_model_line, prefs.defaultModel()?.name ?: "-"))
            append('\n')
            append(
                getString(
                    R.string.settings_models_ready,
                    if (ready.isEmpty()) "無" else ready.joinToString("、") { it.name },
                ),
            )
            append('\n')
            append(getString(R.string.settings_models_used, ModelCatalog.human(used)))
        }
    }

    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
}
