package app.dsh.asr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000

/** 音訊小工具：能量計算 */
object AudioUtil {

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    fun db(rms: Float): Float = if (rms <= 1e-6f) -120f else 20f * log10(rms)
}

/** 一次辨識請求的參數（由 RecognitionService 收到的 Intent 解析而來） */
data class RequestParams(
    val langHint: String,       // auto | zh | en
    val silenceMs: Int,
    val maxUtteranceMs: Int,
    val noSpeechTimeoutMs: Int,
)

/**
 * 引擎快取：同一行程內重複使用 sherpa 辨識器（載入 240MB 模型代價高，不能每次工作階段重建）。
 * 注意：sherpa 的 Recognizer 不是執行緒安全的，呼叫方需自行確保依序執行（本 App 同時只有一個工作階段）。
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var cachedKey: String? = null
    private var offline: OfflineRecognizer? = null

    private var offlineCfg: OfflineRecognizerConfig? = null
    private var offlineLang: String? = null

    @Synchronized
    fun offline(ctx: Context, model: AsrModel, lang: String): OfflineRecognizer {
        // F21: the key no longer includes the language. Loading takes ~9 s on the
        // F21, so a language switch (TT9 中文 <-> English) only updates the config;
        // SenseVoice reads the language from the config at every decode.
        val key = "offline:${model.id}"
        if (key == cachedKey) offline?.let { r ->
            if (lang != offlineLang) {
                offlineCfg?.let { cfg ->
                    cfg.modelConfig.senseVoice.language = lang
                    r.setConfig(cfg)
                    Log.i(TAG, "language switched ${offlineLang} -> $lang without reload")
                }
                offlineLang = lang
            }
            return r
        }
        clearLocked()
        val t0 = SystemClock.elapsedRealtime()
        val r = buildOffline(ctx, model, lang)
        Log.i(TAG, "offline recognizer ready in ${SystemClock.elapsedRealtime() - t0} ms (${model.id}, lang=$lang)")
        cachedKey = key
        offline = r
        offlineLang = lang
        return r
    }

    @Synchronized
    fun clear() = clearLocked()

    private fun clearLocked() {
        runCatching { offline?.release() }
        offline = null
        cachedKey = null
        offlineCfg = null
        offlineLang = null
    }

    private fun pathOf(ctx: Context, model: AsrModel, prefix: String): String {
        val store = ModelStore(ctx)
        val f = model.files.firstOrNull { it.name.startsWith(prefix) }
            ?: throw IllegalStateException("model ${model.id} missing file *$prefix*")
        return store.fileOf(model, f).absolutePath
    }

    private fun buildOffline(ctx: Context, model: AsrModel, lang: String): OfflineRecognizer {
        val store = ModelStore(ctx)
        val tokens = model.files.first { it.name == "tokens.txt" }
        val mc = OfflineModelConfig().apply {
            this.tokens = store.fileOf(model, tokens).absolutePath
            numThreads = 4  // F21 (MT6761, 4x A53): 4 threads decode ~1.5x faster than 2
            debug = false
            provider = "cpu"
        }
        mc.modelType = "sensevoice"
        mc.senseVoice = OfflineSenseVoiceModelConfig().apply {
            this.model = pathOf(ctx, model, "model")
            this.language = lang
            useInverseTextNormalization = true
        }
        val cfg = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(SAMPLE_RATE, 80, 0.0f)
            modelConfig = mc
            decodingMethod = "greedy_search"
        }
        offlineCfg = cfg
        // 模型放在外部私有目錄（SD 卡路徑）：必須傳 null AssetManager，
        // 否則 sherpa 原生層會拒絕載入並 abort 行程（file-utils.cc: ReadFile）。
        return OfflineRecognizer(null, cfg)
    }
}

/** 一次辨識工作階段：錄音 → 送入引擎 → 回呼結果。執行在獨立執行緒，避免阻塞服務主執行緒。 */
class CaptureSession(
    private val ctx: Context,
    private val callback: android.speech.RecognitionService.Callback,
    private val model: AsrModel,
    private val params: RequestParams,
    private val langCode: String,
    private val onDone: (CaptureSession) -> Unit,
) {

    private val stopFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private var thread: Thread? = null
    private var released = false

    fun start() {
        thread = Thread({ safeRun() }, "asr-capture").apply {
            isDaemon = true
            start()
        }
    }

    /** 呼叫方 stopListening()：把已有音訊當作"使用者說完"處理，傳回最終結果 */
    fun requestStop() = stopFlag.set(true)

    /** 呼叫方 cancel()：丟棄結果，立即結束 */
    fun cancel() {
        cancelFlag.set(true)
        stopFlag.set(true)
    }

    private fun safeRun() {
        try {
            run()
        } catch (t: Throwable) {
            Log.e(TAG, "session failed: ${t.message}", t)
            if (!cancelFlag.get()) safeError(errorFor(t))
        } finally {
            released = true
            onDone(this)
        }
    }

    private fun errorFor(t: Throwable): Int = when (t) {
        is SecurityException -> android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        is IllegalStateException -> android.speech.SpeechRecognizer.ERROR_AUDIO
        else -> android.speech.SpeechRecognizer.ERROR_AUDIO
    }

    private fun run() {
        Log.i(TAG, "session start: model=${model.id} lang=$langCode params=$params")
        val recorder = openRecorder()
        var ok = false
        try {
            recorder.startRecording()
            ok = true
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            runCatching { recorder.release() }
            safeError(android.speech.SpeechRecognizer.ERROR_AUDIO)
            return
        }
        try {
            safe { callback.readyForSpeech(android.os.Bundle()) }
            // F21: short buzz once the mic is recording, so the user knows to speak.
            buzz()
            runOffline(recorder)
        } finally {
            if (ok) {
                runCatching { recorder.stop() }
            }
            runCatching { recorder.release() }
            Log.i(TAG, "session end: model=${model.id}")
        }
    }

    // ------------------------------------------------------------------ 離線

    private fun runOffline(recorder: AudioRecord) {
        // F21: record first; the recognizer is only needed for the final decode,
        // so a model still loading does not block listening.
        val recognizer by lazy { EngineHolder.offline(ctx, model, langCode) }
        val buf = FloatBuffer()
        val chunk = ShortArray(CHUNK_SHORTS)
        var noiseFloor = 0.004f
        var speechStarted = false
        var voiceRun = 0
        var lastVoiceAt = 0L
        val startAt = SystemClock.elapsedRealtime()
        var reason = "loop"

        while (true) {
            if (cancelFlag.get()) { reason = "cancel"; break }
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    throw IllegalStateException("AudioRecord.read error $n")
                }
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val samples = FloatArray(n) { chunk[it] / 32768f }
            buf.append(samples)

            val level = AudioUtil.rms(samples)
            if (level < noiseFloor) noiseFloor = noiseFloor * 0.92f + level * 0.08f
            val threshold = max(0.012f, noiseFloor * 3.0f)
            if (level > threshold) {
                voiceRun++
                lastVoiceAt = now
                if (!speechStarted && voiceRun >= 2) {
                    speechStarted = true
                    Log.i(TAG, "speech detected (level=$level floor=$noiseFloor)")
                    safe { callback.beginningOfSpeech() }
                }
            } else {
                voiceRun = 0
            }
            if (speechStarted) safe { callback.rmsChanged(AudioUtil.db(level)) }

            if (stopFlag.get()) { reason = "stop"; break }
            if (!speechStarted && now - startAt > params.noSpeechTimeoutMs) { reason = "no-speech"; break }
            if (speechStarted && now - lastVoiceAt > params.silenceMs) { reason = "silence"; break }
            if (now - startAt > params.maxUtteranceMs) { reason = "max-length"; break }
        }

        if (cancelFlag.get()) return

        if (reason == "no-speech") {
            Log.i(TAG, "no speech detected within ${params.noSpeechTimeoutMs} ms")
            safeError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            return
        }
        val all = buf.toFloatArray()
        val padded = FloatArray(all.size + SAMPLE_RATE / 3)      // 尾部補 300ms 靜音，幫模型收尾
        System.arraycopy(all, 0, padded, 0, all.size)
        val text = if (padded.size > SAMPLE_RATE / 2) runCatching {
            decodeOffline(recognizer, padded)
        }.onFailure { Log.e(TAG, "final decode failed", it) }.getOrDefault("")
        else ""
        Log.i(TAG, "final text ($reason): $text")
        deliver(text, speechStarted)
    }

    private fun decodeOffline(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    // ------------------------------------------------------------------ 共用

    private fun deliver(text: String, speechStarted: Boolean) {
        if (text.isEmpty()) {
            safeError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        if (speechStarted) safe { callback.endOfSpeech() }
        safe { callback.results(resultBundle(text)) }
    }

    private fun resultBundle(text: String): android.os.Bundle = android.os.Bundle().apply {
        // F21: output Traditional Chinese with Taiwan character forms (OpenCC s2tw chain).
        val out = runCatching { SpokenPunct.englishCase(SpokenPunct.apply(SpokenPunct.dropAutoFinalStop(TwConverter.convert(ctx, text)))) }
            .onFailure { Log.e(TAG, "s2tw failed", it) }.getOrDefault(text)
        Log.i(TAG, "delivered: $out")
        putStringArrayList(
            android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf(out),
        )
        putFloatArray(android.speech.SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(-1f))
        putString(EXTRA_ENGINE, "${model.name} · sherpa-onnx")
    }

    private fun buzz() {
        runCatching {
            val v = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createOneShot(BUZZ_MS, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "vibrate failed: ${it.message}") }
    }

    private fun safeError(code: Int) {
        Log.i(TAG, "error($code)")
        safe { callback.error(code) }
    }

    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "callback failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 錄音

    private fun openRecorder(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("getMinBufferSize=$minBuf")
        val bufferBytes = max(minBuf, SAMPLE_RATE * 2 * 4)   // 約 4 秒緩衝
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION

        // API 31+：按官方建議把麥克風存取歸因到呼叫方（辨識失敗則退回一般 context）
        if (Build.VERSION.SDK_INT >= 31) {
            val attributed = runCatching {
                val attribCtx = ctx.createContext(
                    android.content.ContextParams.Builder()
                        .setNextAttributionSource(callback.callingAttributionSource)
                        .build(),
                )
                AudioRecord.Builder()
                    .setContext(attribCtx)
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull()
            if (attributed != null && attributed.state == AudioRecord.STATE_INITIALIZED) return attributed
            attributed?.release()
        }

        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrElse {
            @Suppress("DEPRECATION")
            AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
            )
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            throw IllegalStateException("AudioRecord not initialized")
        }
        return rec
    }

    companion object {
        private const val TAG = "CaptureSession"
        private const val CHUNK_SHORTS = SAMPLE_RATE / 10          // 100ms
        private const val BUZZ_MS = 80L
        const val EXTRA_ENGINE = "app.dsh.asr.ENGINE"
    }
}

/** 只增不改的 float 環形緩衝（工作階段內音訊累積） */
class FloatBuffer(initialCapacity: Int = SAMPLE_RATE * 8) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun append(samples: FloatArray) {
        if (size + samples.size > data.size) {
            var cap = data.size * 2
            while (cap < size + samples.size) cap *= 2
            data = data.copyOf(cap)
        }
        System.arraycopy(samples, 0, data, size, samples.size)
        size += samples.size
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}
