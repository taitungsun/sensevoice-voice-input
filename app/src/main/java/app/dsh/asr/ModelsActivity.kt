package app.dsh.asr

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/** 模型管理頁：下載（斷點續傳+驗證）、取消、刪除 */
class ModelsActivity : Activity(), DownloadBus.Listener {

    private lateinit var store: ModelStore
    private lateinit var prefs: Prefs
    private lateinit var adapter: ModelAdapter

    private val progress = HashMap<String, Pair<Long, Long>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)

        store = ModelStore(this)
        prefs = Prefs(this)
        adapter = ModelAdapter(
            ctx = this,
            onDownload = { model -> toggleDownload(model) },
            onDelete = { model -> confirmDelete(model) },
        )

        adapter.attach(findViewById(R.id.listModels))
        findViewById<TextView>(R.id.tvDir).text = getString(R.string.models_dir, store.root.absolutePath)
    }

    override fun onResume() {
        super.onResume()
        DownloadBus.register(this)
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        DownloadBus.unregister(this)
        super.onPause()
    }

    private fun toggleDownload(model: AsrModel) {
        if (DownloadBus.isRunning(model.id)) {
            DownloadBus.cancel(model.id)
            Ui.toast(this, "正在取消…")
        } else {
            DownloadBus.start(this, model)
            Ui.toast(this, "開始下載 ${model.name}")
        }
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(model: AsrModel) {
        AlertDialog.Builder(this)
            .setTitle("刪除 ${model.name}？")
            .setMessage("將刪除本機模型檔案（${ModelCatalog.human(store.storedBytes(model))}）")
            .setPositiveButton("刪除") { _, _ ->
                DownloadBus.cancel(model.id)
                store.delete(model)
                progress.remove(model.id)
                Ui.toast(this, getString(R.string.toast_deleted, model.name))
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onProgress(modelId: String, done: Long, total: Long) {
        progress[modelId] = done to total
        adapter.notifyDataSetChanged()
    }

    override fun onFinished(modelId: String, ok: Boolean, message: String?) {
        progress.remove(modelId)
        val model = ModelCatalog.byId(modelId)
        if (ok) {
            Ui.toast(this, getString(R.string.toast_download_done, model?.name ?: modelId))
        } else if (message != "已取消") {
            Ui.toast(this, getString(R.string.toast_download_failed, message ?: "未知錯誤"))
        }
        adapter.notifyDataSetChanged()
    }

    private inner class ModelAdapter(
        private val ctx: Context,
        private val onDownload: (AsrModel) -> Unit,
        private val onDelete: (AsrModel) -> Unit,
    ) {
        // 每個模型一個固定的 View；更新時只改內容，不重建，D-pad 焦點才不會在下載進度更新時跑掉
        private val views = ArrayList<View>()

        fun attach(container: LinearLayout) {
            ModelCatalog.MODELS.forEach { _ ->
                val v = LayoutInflater.from(ctx).inflate(R.layout.item_model, container, false)
                container.addView(v)
                views.add(v)
            }
            notifyDataSetChanged()
        }

        fun notifyDataSetChanged() {
            views.forEachIndexed { i, v -> bind(i, v) }
        }

        private fun bind(position: Int, view: View): View {
            val model = ModelCatalog.MODELS[position]
            val state = store.state(model)
            val running = DownloadBus.isRunning(model.id)

            view.findViewById<TextView>(R.id.tvName).text = model.name
            view.findViewById<TextView>(R.id.tvKind).text = getString(R.string.label_offline)

            view.findViewById<TextView>(R.id.tvMeta).text =
                "${model.langs} · ${ModelCatalog.human(model.totalBytes)}\n${model.note}"

            val tvState = view.findViewById<TextView>(R.id.tvState)
            val bar = view.findViewById<ProgressBar>(R.id.progress)
            val p = progress[model.id]
            when {
                running && p != null -> {
                    val pct = if (p.second > 0) (p.first * 100 / p.second).toInt() else 0
                    tvState.text = String.format(
                        Locale.US, "下載中 %d%% · %s / %s",
                        pct, ModelCatalog.human(p.first), ModelCatalog.human(p.second),
                    )
                    tvState.setTextColor(Ui.ACCENT)
                    bar.visibility = View.VISIBLE
                    bar.progress = pct
                }
                running -> {
                    tvState.text = "下載中…（正在連線 ${ModelCatalog.BASE.substringAfter("://")}）"
                    tvState.setTextColor(Ui.ACCENT)
                    bar.visibility = View.VISIBLE
                    bar.isIndeterminate = true
                }
                else -> {
                    tvState.text = when (state) {
                        ModelState.READY -> getString(R.string.state_ready) +
                            " · " + ModelCatalog.human(model.totalBytes) + " · 已在本機驗證"
                        ModelState.PARTIAL -> "未完成 · 已下載 " +
                            ModelCatalog.human(store.storedBytes(model)) + "（可繼續下載）"
                        ModelState.MISSING -> getString(R.string.state_missing)
                    }
                    tvState.setTextColor(
                        when (state) {
                            ModelState.READY -> Ui.OK
                            ModelState.PARTIAL -> Ui.WARN
                            ModelState.MISSING -> Ui.TEXT_DIM
                        },
                    )
                    bar.visibility = View.GONE
                    bar.isIndeterminate = false
                }
            }

            val btnDownload = view.findViewById<TextView>(R.id.btnDownload)
            btnDownload.text = when {
                running -> getString(R.string.btn_cancel)
                state == ModelState.READY -> "重新下載"
                state == ModelState.PARTIAL -> "繼續下載"
                else -> getString(R.string.btn_download)
            }
            btnDownload.contentDescription = getString(R.string.a11y_download, btnDownload.text, model.name)
            btnDownload.setOnClickListener { onDownload(model) }

            val btnDelete = view.findViewById<TextView>(R.id.btnDelete)
            btnDelete.alpha = if (state == ModelState.MISSING) 0.4f else 1f
            btnDelete.contentDescription = getString(R.string.a11y_download, btnDelete.text, model.name)
            btnDelete.setOnClickListener {
                if (state == ModelState.MISSING) Ui.toast(ctx, "本機沒有這個模型")
                else onDelete(model)
            }
            return view
        }
    }
}
