package app.dsh.asr

import android.content.Context
import android.util.Log

/**
 * Simplified -> Traditional Chinese with Taiwan character forms, the same chain
 * as OpenCC's s2tw: STPhrases + STCharacters, then TWVariants.
 * Each step is a forward maximum-match over the text. Dictionaries come from
 * OpenCC (Apache-2.0) and live in assets/opencc.
 *
 * TWPhrases (s2twp's word replacement) is deliberately NOT used: it rewrites
 * words that are normal in Taiwan too (支持→支援, 程序→程式, 文件→檔案,
 * 數據→資料), so the user's own words would be changed.
 */
object TwConverter {

    private const val TAG = "TwConverter"

    private class Dict(val map: HashMap<String, String>, val maxLen: Int)

    @Volatile private var chain: List<Dict>? = null

    fun convert(ctx: Context, text: String): String {
        if (text.isEmpty()) return text
        val steps = chain ?: synchronized(this) {
            chain ?: load(ctx).also { chain = it }
        }
        var out = text
        for (d in steps) out = apply(d, out)
        return out
    }

    private fun load(ctx: Context): List<Dict> {
        val t0 = System.currentTimeMillis()
        val st = HashMap<String, String>(60_000)
        var stMax = 1
        stMax = maxOf(stMax, read(ctx, "opencc/STCharacters.txt", st))
        stMax = maxOf(stMax, read(ctx, "opencc/STPhrases.txt", st))
        val twv = HashMap<String, String>(64)
        val twvMax = read(ctx, "opencc/TWVariants.txt", twv)
        Log.i(TAG, "dictionaries loaded in ${System.currentTimeMillis() - t0} ms")
        return listOf(Dict(st, stMax), Dict(twv, twvMax))
    }

    /** Reads "key<TAB>value [alt ...]" lines; keeps the first value. Returns the longest key length. */
    private fun read(ctx: Context, asset: String, into: HashMap<String, String>): Int {
        var max = 1
        ctx.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                val rest = line.substring(tab + 1)
                val sp = rest.indexOf(' ')
                into[key] = if (sp < 0) rest else rest.substring(0, sp)
                if (key.length > max) max = key.length
            }
        }
        return max
    }

    private fun apply(d: Dict, s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            var matched = false
            var len = minOf(d.maxLen, s.length - i)
            while (len > 0) {
                val v = d.map[s.substring(i, i + len)]
                if (v != null) {
                    sb.append(v)
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }
}
