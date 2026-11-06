package app.dsh.asr

/**
 * F21: spoken punctuation. SenseVoice writes the word ("逗號") instead of the
 * mark, so the words are replaced after the Taiwan-wording conversion.
 * Longer words come first so "驚嘆號" wins over shorter overlaps.
 */
object SpokenPunct {

    private val MAP = listOf(
        "驚嘆號" to "！",
        "感嘆號" to "！",
        "刪節號" to "……",
        "省略號" to "……",
        "逗號" to "，",
        "逗點" to "，",
        "句號" to "。",
        "句點" to "。",
        "問號" to "？",
        "冒號" to "：",
        "分號" to "；",
        "頓號" to "、",
        "換行" to "\n",
    )

    /**
     * SenseVoice writes English in capitals ("TEST IT IS A TEST"). Lower-case it,
     * then capitalise the first letter, letters after . ? ! (and full-width
     * 。？！), and the word "I" (also I'm, I'll, I've, I'd).
     */
    fun englishCase(text: String): String {
        if (text.none { it in 'A'..'Z' }) return text
        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        var capNext = true
        for (ch in lower) {
            if (capNext && ch in 'a'..'z') {
                sb.append(ch.uppercaseChar()); capNext = false
            } else {
                sb.append(ch)
                if (ch in 'a'..'z' || ch.code > 0x2E7F) capNext = false
            }
            if (ch == '.' || ch == '?' || ch == '!' || ch == '。' || ch == '？' || ch == '！' || ch == '\n') capNext = true
        }
        return Regex("\\bi\\b(?=$|[\\s'’,.?!])").replace(sb.toString(), "I")
    }

    /** English spoken punctuation; longer phrases first. Matched as whole words, any case. */
    private val EN = listOf(
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "question mark" to "?",
        "full stop" to ".",
        "new line" to "\n",
        "semicolon" to ";",
        "period" to ".",
        "comma" to ",",
        "colon" to ":",
    ).map { (w, m) ->
        // Eat the space before the word ("hello comma" -> "hello,"); keep the one after.
        Regex("\\s*\\b" + w.replace(" ", "\\s+") + "\\b", RegexOption.IGNORE_CASE) to m
    }

    fun apply(text: String): String {
        var out = text
        for ((word, mark) in MAP) out = out.replace(word, mark)
        if (out.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            for ((re, mark) in EN) out = re.replace(out) { mark }
            // "\n" should not leave a leading space on the next line.
            out = out.replace(Regex("\n[ \t]+"), "\n")
        }
        // Drop a punctuation mark the model added right next to a spoken one, e.g. "，，" or "，。".
        out = out.replace(Regex("([，。？！：；、])[，。？！：；、]+"), "$1")
        return out
    }
}
