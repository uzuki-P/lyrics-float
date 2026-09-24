package dev.lyricsfloat.lyrics

import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline Japanese romanization, ported from Metrolist: kuromoji-ipadic
 * tokenization plus a katakana-to-romaji map (digraphs, dakuten, sokuon
 * doubling, chōonpu). Japanese only for now; other languages later.
 */
object JapaneseRomaji {
    private val tokenizer: Tokenizer by lazy { Tokenizer() }

    // Kuromoji can split compounds into tokens with readings that are valid
    // individually but wrong together. These use common spoken readings;
    // ambiguous poetic readings still need furigana or a song-specific override.
    private val readingOverrides = mapOf(
        "今日" to "キョウ",
        "昨日" to "キノウ",
        "明日" to "アシタ",
        "一昨日" to "オトトイ",
        "一昨年" to "オトトシ",
        "今朝" to "ケサ",
        "今夜" to "コンヤ",
        "明後日" to "アサッテ",
        "一人" to "ヒトリ",
        "二人" to "フタリ",
        "二十歳" to "ハタチ",
        "二十日" to "ハツカ",
        "大人" to "オトナ",
        "時計" to "トケイ",
        "田舎" to "イナカ",
        "風邪" to "カゼ",
        "土産" to "ミヤゲ",
        "果物" to "クダモノ",
        "部屋" to "ヘヤ",
        "眼鏡" to "メガネ",
        "八百屋" to "ヤオヤ",
    )

    fun isJapanese(text: String): Boolean = text.any { char ->
        (char in '\u3040'..'\u309F') || // Hiragana
            (char in '\u30A0'..'\u30FF') || // Katakana
            (char in '\u4E00'..'\u9FFF') // CJK Unified Ideographs
    }

    fun isChinese(text: String): Boolean {
        if (text.isEmpty()) return false
        val cjk = text.count { it in '\u4E00'..'\u9FFF' }
        val kana = text.count { (it in '\u3040'..'\u309F') || (it in '\u30A0'..'\u30FF') }
        return cjk > 0 && (kana.toDouble() / text.length) < 0.1
    }

    suspend fun romanize(text: String): String = withContext(Dispatchers.Default) {
        val textWithOverrides = readingOverrides.entries
            .sortedByDescending { (phrase, _) -> phrase.length }
            .fold(text) { current, (phrase, reading) -> current.replace(phrase, reading) }
        val tokens = tokenizer.tokenize(textWithOverrides)
        tokens.mapIndexed { index, token ->
            val reading = token.reading?.takeIf { it.isNotEmpty() && it != "*" } ?: token.surface
            val nextReading = tokens.getOrNull(index + 1)
                ?.let { it.reading?.takeIf { r -> r.isNotEmpty() && r != "*" } ?: it.surface }
            katakanaToRomaji(reading, nextReading)
        }.joinToString(" ")
    }

    private val KANA_ROMAJI_MAP: Map<String, String> = mapOf(
        // Digraphs (Yōon)
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        // Basic katakana
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
        "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke", "コ" to "ko",
        "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so",
        "タ" to "ta", "チ" to "chi", "ツ" to "tsu", "テ" to "te", "ト" to "to",
        "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
        "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho",
        "マ" to "ma", "ミ" to "mi", "ム" to "mu", "メ" to "me", "モ" to "mo",
        "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo",
        "ラ" to "ra", "リ" to "ri", "ル" to "ru", "レ" to "re", "ロ" to "ro",
        "ワ" to "wa", "ヲ" to "o", "ン" to "n",
        // Dakuten
        "ガ" to "ga", "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go",
        "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze", "ゾ" to "zo",
        "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do",
        // Handakuten
        "バ" to "ba", "ビ" to "bi", "ブ" to "bu", "ベ" to "be", "ボ" to "bo",
        "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po",
        // Chōonpu (long vowel mark): swallowed, like Metrolist
        "ー" to "",
    )

    private fun katakanaToRomaji(katakana: String?, nextKatakana: String? = null): String {
        if (katakana.isNullOrEmpty()) return ""

        val sb = StringBuilder(katakana.length)
        var i = 0
        val n = katakana.length
        while (i < n) {
            var consumed = false
            if (i + 1 < n) {
                KANA_ROMAJI_MAP[katakana.substring(i, i + 2)]?.let { twoChar ->
                    sb.append(twoChar)
                    i += 2
                    consumed = true
                }
            }

            // Sokuon ッ doubles the first consonant of the next syllable —
            // normally the next char in this token; at a token edge, the next
            // token's reading (Metrolist only handles the edge case).
            if (!consumed && katakana[i] == 'ッ') {
                val nextChar = katakana.getOrNull(i + 1) ?: nextKatakana?.getOrNull(0)
                if (nextChar != null) {
                    val romaji = KANA_ROMAJI_MAP[nextChar.toString()]?.getOrNull(0)?.toString()
                        ?: nextChar.toString()
                    sb.append(romaji.lowercase().trim())
                }
                i += 1
                consumed = true
            }

            if (!consumed) {
                val one = katakana[i].toString()
                sb.append(KANA_ROMAJI_MAP[one] ?: one)
                i += 1
            }
        }
        return sb.toString().lowercase()
    }
}
