/**
 * 唤醒词存储与关键词串构建。
 *
 * - 预置词来自 assets keywords.txt(格式:`声母 韵母 … @显示名`,带调韵母);
 * - 自定义词存 Prefs(JSON 列表),由汉字经 pinyin4j 转带调拼音后按声母/韵母切分生成关键词行;
 * - [buildActiveKeywords] 输出 `createStream(keywords)` 所需串:null = 使用全部预置词。
 *
 * 归属模块：wake
 */
package com.brycewg.asrkb.wake

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CustomWakeWord(
    val name: String,
    /** 关键词 token 串,如 "x iǎo ài t óng x ué"(不含 @显示名) */
    val tokens: String
)

internal object WakeWordStore {

    private const val TAG = "WakeWordStore"
    private const val KEYWORDS_ASSET = "kws/wenetspeech-3.3M/keywords.txt"
    private const val TOKENS_ASSET = "kws/wenetspeech-3.3M/tokens.txt"
    private val JSON = Json { ignoreUnknownKeys = true }

    /** 声母表(先匹配双声母) */
    private val INITIALS = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "r", "z", "c", "s", "y", "w"
    )

    // ==================== 预置词 ====================

    /** keywords.txt 全部行(含 @显示名) */
    fun presetLines(context: Context): List<String> = try {
        context.assets.open(KEYWORDS_ASSET).bufferedReader().readLines().filter { it.isNotBlank() }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to read keywords asset", t)
        emptyList()
    }

    /** 预置词显示名列表 */
    fun presetNames(context: Context): List<String> =
        presetLines(context).map { it.substringAfterLast('@') }.filter { it.isNotBlank() }

    // ==================== 自定义词 ====================

    fun loadCustom(context: Context): List<CustomWakeWord> = try {
        val prefs = com.brycewg.asrkb.store.Prefs(context)
        JSON.decodeFromString(
            ListSerializer(CustomWakeWord.serializer()),
            prefs.wakeWordCustomJson
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load custom wake words", t)
        emptyList()
    }

    fun saveCustom(context: Context, words: List<CustomWakeWord>) {
        val prefs = com.brycewg.asrkb.store.Prefs(context)
        prefs.wakeWordCustomJson =
            JSON.encodeToString(ListSerializer(CustomWakeWord.serializer()), words)
    }

    fun customNames(context: Context): List<String> = loadCustom(context).map { it.name }

    // ==================== 汉字 → 关键词行 ====================

    /** 读模型 token 表(带调韵母/声母),用于校验生成的词可被模型接受 */
    private fun tokenSet(context: Context): Set<String> = try {
        context.assets.open(TOKENS_ASSET).bufferedReader().readLines()
            .map { it.substringBefore(' ').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to read tokens asset", t)
        emptySet()
    }

    // pinyin4j 2.5.1 三声用短音符(ăĕĭŏŭ),模型 tokens.txt 用标准第三声符号(ǎěǐǒǔ),需归一化
    private val TONE3_NORMALIZE = mapOf(
        'ă' to 'ǎ', 'ĕ' to 'ě', 'ĭ' to 'ǐ', 'ŏ' to 'ǒ', 'ŭ' to 'ǔ'
    )

    private fun normalizeTone3(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) b.append(TONE3_NORMALIZE[c] ?: c)
        return b.toString()
    }

    /**
     * 汉字文本 → 关键词行(“token 串 @文本”)。
     * @return null 表示包含模型不支持的读音/不符合输入要求
     */
    fun textToKeywordLine(context: Context, text: String): String? {
        val trimmed = text.trim()
        if (!Regex("^[\u4e00-\u9fa5]{2,5}$").matches(trimmed)) return null

        val tokens = tokenSet(context)
        if (tokens.isEmpty()) return null

        val formatter = net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat().apply {
            caseType = net.sourceforge.pinyin4j.format.HanyuPinyinCaseType.LOWERCASE
            toneType = net.sourceforge.pinyin4j.format.HanyuPinyinToneType.WITH_TONE_MARK
            vCharType = net.sourceforge.pinyin4j.format.HanyuPinyinVCharType.WITH_U_UNICODE
        }

        val out = StringBuilder()
        for (ch in trimmed) {
            val pronunciations = try {
                net.sourceforge.pinyin4j.PinyinHelper.toHanyuPinyinStringArray(ch, formatter)
            } catch (t: Throwable) {
                Log.w(TAG, "Pinyin conversion failed for $ch", t)
                return null
            }
            val syllable = pronunciations?.firstOrNull()
                ?.let(::normalizeTone3) ?: return null

            // 切分:最长声母前缀 + 带调韵母;零声母音节整节为韵母
            var initial = ""
            for (candidate in INITIALS) {
                if (syllable.length > candidate.length && syllable.startsWith(candidate)) {
                    initial = candidate
                    break
                }
            }
            val final = syllable.removePrefix(initial)

            if (initial.isNotEmpty() && initial !in tokens) return null
            if (final.isEmpty() || final !in tokens) {
                Log.w(TAG, "Final not in model vocab: $final (from $syllable)")
                return null
            }
            if (initial.isNotEmpty()) out.append(initial).append(' ')
            out.append(final).append(' ')
        }
        val tokenPart = out.toString().trim()
        if (tokenPart.isEmpty()) return null
        return "$tokenPart @$trimmed"
    }

    // ==================== 生效关键词串 ====================

    /**
     * 构建 createStream(keywords) 的关键词串。
     * @param selected 选中的显示名;空 = 全部预置词(返回 null 使用默认 keywordsFile)
     */
    fun buildActiveKeywords(context: Context, selected: String): String? {
        val sel = selected.trim()
        if (sel.isEmpty()) return null

        // 预置词:取对应行
        presetLines(context)
            .firstOrNull { it.substringAfterLast('@') == sel }
            ?.let { return it }

        // 自定义词:生成行
        loadCustom(context).firstOrNull { it.name == sel }?.let {
            return "${it.tokens} @${it.name}"
        }

        return null
    }
}
