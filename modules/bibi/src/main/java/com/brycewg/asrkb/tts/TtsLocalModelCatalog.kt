/**
 * 本地 TTS 模型目录：变体规格（family/音色表）、安装目录约定、就绪检查与清除。
 *
 * 目录约定：getExternalFilesDir(null)/tts/<variant>/，与 ASR 本地模型
 * （sensevoice/<variant> 等）一致；模型包为 sherpa-onnx 官方 tts-models
 * 发布资产（tar.bz2）。
 *
 * family 决定引擎反射构造路径：
 * - VITS：OfflineTtsVitsModelConfig(model/lexicon/tokens/dataDir/dictDir)，
 *   覆盖 piper zh（lexicon+fst 布局，如 xiao_ya/chaowen；espeak 布局如 huayan）
 *   与 melo zh_en（lexicon + 可选 dict/）；
 * - KOKORO：OfflineTtsKokoroModelConfig(model/voices/tokens/dataDir/lexicon)，
 *   中英混合（kokoro-multi-lang 系列，必须含 voices.bin + espeak-ng-data/，
 *   中文走词库、英文 OOV 走 espeak-ng G2P）。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import java.io.File

object TtsLocalModelCatalog {
    private const val TAG = "TtsLocalModelCatalog"

    /** ModelDownloadService 的 modelType 标识 */
    const val MODEL_TYPE = "tts_offline"

    /** 模型根目录（位于 getExternalFilesDir 下） */
    const val MODEL_ROOT_DIR = "tts"

    /** 引擎配置族：决定 OfflineTtsModelConfig 内填充哪个子配置 */
    enum class TtsModelFamily { VITS, KOKORO }

    /** 音色项：sid + 显示文案（labelRes 为格式串时以 labelArg 填充序号） */
    data class TtsVoiceSpec(
        val sid: Int,
        val labelRes: Int,
        val labelArg: Int = 0
    )

    data class TtsVariantSpec(
        val id: String,
        val labelRes: Int,
        val downloadUrl: String,
        val family: TtsModelFamily = TtsModelFamily.VITS,
        /** 可选音色表；首项为默认 sid；size<=1 不显示音色选择 */
        val voices: List<TtsVoiceSpec> = listOf(TtsVoiceSpec(0, R.string.tts_voice_default))
    )

    // ---- kokoro v1.1 中文音色 sid 表 ----
    // 官方 voices 导出顺序（generate_voices_bin.py）：sid 0-2 为英文音色，
    // 之后按zf_001..zf_099（女声 55 个）→ zm_009..zm_100（男声 45 个）排列，
    // 故女声 sid 3..57、男声 sid 58..102；不列英文音色（英文音色读不了中文）。
    private const val KOKORO_V11_FEMALE_SID_START = 3
    private const val KOKORO_V11_FEMALE_COUNT = 55
    private const val KOKORO_V11_MALE_SID_START = 58
    private const val KOKORO_V11_MALE_COUNT = 45

    private fun kokoroV11Voices(): List<TtsVoiceSpec> = buildList {
        repeat(KOKORO_V11_FEMALE_COUNT) { i ->
            add(
                TtsVoiceSpec(
                    KOKORO_V11_FEMALE_SID_START + i,
                    R.string.tts_voice_female_fmt,
                    i + 1
                )
            )
        }
        repeat(KOKORO_V11_MALE_COUNT) { i ->
            add(
                TtsVoiceSpec(
                    KOKORO_V11_MALE_SID_START + i,
                    R.string.tts_voice_male_fmt,
                    i + 1
                )
            )
        }
    }

    // kokoro v1.0：8 个具名中文音色（官方文档 sid 45-52）
    private val KOKORO_V10_VOICES = listOf(
        TtsVoiceSpec(45, R.string.tts_voice_v10_xiaobei),
        TtsVoiceSpec(46, R.string.tts_voice_v10_xiaoni),
        TtsVoiceSpec(47, R.string.tts_voice_v10_xiaoxiao),
        TtsVoiceSpec(48, R.string.tts_voice_v10_xiaoyi),
        TtsVoiceSpec(49, R.string.tts_voice_v10_yunjian),
        TtsVoiceSpec(50, R.string.tts_voice_v10_yunxi),
        TtsVoiceSpec(51, R.string.tts_voice_v10_yunxia),
        TtsVoiceSpec(52, R.string.tts_voice_v10_yunyang)
    )

    // ---- 变体定义（推荐的中英混合模型置顶） ----

    val VARIANT_KOKORO_INT8_V11 = TtsVariantSpec(
        id = "kokoro-int8-v1_1",
        labelRes = R.string.tts_model_kokoro_int8_v11,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
        family = TtsModelFamily.KOKORO,
        voices = kokoroV11Voices()
    )

    val VARIANT_KOKORO_V11 = TtsVariantSpec(
        id = "kokoro-v1_1",
        labelRes = R.string.tts_model_kokoro_v11,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2",
        family = TtsModelFamily.KOKORO,
        voices = kokoroV11Voices()
    )

    val VARIANT_KOKORO_INT8_V10 = TtsVariantSpec(
        id = "kokoro-int8-v1_0",
        labelRes = R.string.tts_model_kokoro_int8_v10,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        family = TtsModelFamily.KOKORO,
        voices = KOKORO_V10_VOICES
    )

    val VARIANT_MELO = TtsVariantSpec(
        id = "melo-zh_en",
        labelRes = R.string.tts_model_melo_zh_en,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2"
    )

    val VARIANT_MEDIUM = TtsVariantSpec(
        id = "medium",
        labelRes = R.string.tts_model_medium,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium.tar.bz2"
    )

    val VARIANT_MEDIUM_INT8 = TtsVariantSpec(
        id = "medium-int8",
        labelRes = R.string.tts_model_medium_int8,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2"
    )

    val VARIANT_CHAOWEN = TtsVariantSpec(
        id = "chaowen",
        labelRes = R.string.tts_model_chaowen,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-chaowen-medium.tar.bz2"
    )

    val VARIANT_CHAOWEN_INT8 = TtsVariantSpec(
        id = "chaowen-int8",
        labelRes = R.string.tts_model_chaowen_int8,
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-chaowen-medium-int8.tar.bz2"
    )

    /** 下拉列表顺序：推荐的中英混合模型在最前 */
    val variants: List<TtsVariantSpec> = listOf(
        VARIANT_KOKORO_INT8_V11,
        VARIANT_KOKORO_V11,
        VARIANT_KOKORO_INT8_V10,
        VARIANT_MELO,
        VARIANT_MEDIUM,
        VARIANT_MEDIUM_INT8,
        VARIANT_CHAOWEN,
        VARIANT_CHAOWEN_INT8
    )

    /** 默认变体：推荐的中英混合模型 */
    fun defaultVariant(): String = VARIANT_KOKORO_INT8_V11.id

    fun normalizeVariant(id: String?): String =
        variants.firstOrNull { it.id == id }?.id ?: defaultVariant()

    fun variantSpec(id: String?): TtsVariantSpec =
        variants.firstOrNull { it.id == id } ?: VARIANT_KOKORO_INT8_V11

    /** 变体默认音色 sid（音色表首项） */
    fun defaultVoiceSid(variant: String): Int = variantSpec(variant).voices.first().sid

    /** 将存储的 sid 解析为合法音色：不在音色表内时回落默认（表变更/脏数据兜底） */
    fun resolveVoiceSid(variant: String, storedSid: Int): Int =
        variantSpec(variant).voices.firstOrNull { it.sid == storedSid }?.sid
            ?: defaultVoiceSid(variant)

    /** 音色显示文案（格式串以 labelArg 填序号） */
    fun voiceLabel(context: Context, variant: String, sid: Int): String {
        val voice = variantSpec(variant).voices.firstOrNull { it.sid == sid }
            ?: return "#$sid"
        return if (voice.labelArg != 0) {
            context.getString(voice.labelRes, voice.labelArg)
        } else {
            context.getString(voice.labelRes)
        }
    }

    fun modelRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODEL_ROOT_DIR)
    }

    fun modelDir(context: Context, variant: String): File =
        File(modelRoot(context), normalizeVariant(variant))

    /**
     * 解压后的模型文件组。除 dir/onnx/tokens 外均为可选（随打包布局而定）。
     */
    data class ModelFiles(
        val dir: File,
        val onnxFile: File,
        val tokensFile: File,
        /** VITS 族单文件词库（lexicon.txt，xiao_ya/chaowen/melo） */
        val lexiconFile: File?,
        val espeakDataDir: File?,
        val dictDir: File?,
        val voicesBinFile: File?,
        /** KOKORO 族多文件词库（lexicon-zh.txt 等），经 [lexiconArg] 逗号拼接传入 */
        val lexiconFiles: List<File>,
        /** 文本规范化规则 fst（phone/number/date 等），逗号拼接后传 ruleFsts */
        val ruleFstFiles: List<File>
    ) {
        fun ruleFstsArg(): String = ruleFstFiles.joinToString(",") { it.absolutePath }

        fun lexiconArg(): String = lexiconFiles.joinToString(",") { it.absolutePath }
    }

    /**
     * 在模型目录内定位文件组：找含 tokens.txt + 至少一个 .onnx 的目录（深度 ≤3）。
     * onnx 按 variant 偏好选取（int8 变体优先 *.int8.onnx，其余优先非 int8）。
     * KOKORO 族另要求 voices.bin + espeak-ng-data/ + lexicon*.txt 齐备——
     * 多语种 kokoro 在 lexicon/lang 双缺时 native 层直接 EXIT(-1) 杀进程，
     * 必须在构造引擎前拦截。
     */
    fun findModelFiles(dir: File, variant: String): ModelFiles? {
        if (!dir.exists()) return null
        val candidateDir = findDirWithTokensAndOnnx(dir) ?: return null
        val wantInt8 = normalizeVariant(variant).contains("int8")
        val onnxFiles = candidateDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".onnx") }
            .orEmpty()
        if (onnxFiles.isEmpty()) return null
        val onnx = onnxFiles.firstOrNull { if (wantInt8) it.name.contains(".int8.") else !it.name.contains(".int8.") }
            ?: onnxFiles.first()
        val tokens = File(candidateDir, "tokens.txt")
        val lexiconFiles = candidateDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            .orEmpty()
        // VITS 用单文件 lexicon.txt；kokoro 目录内无该名文件，走 lexiconFiles 逗号列表
        val lexicon = lexiconFiles.firstOrNull { it.name == "lexicon.txt" }
            ?: lexiconFiles.firstOrNull()
        val espeakData = File(candidateDir, "espeak-ng-data").takeIf { it.isDirectory }
        val dict = File(candidateDir, "dict").takeIf { it.isDirectory }
        val voicesBin = File(candidateDir, "voices.bin").takeIf { it.isFile }
        if (variantSpec(variant).family == TtsModelFamily.KOKORO &&
            (voicesBin == null || espeakData == null || lexiconFiles.isEmpty())
        ) {
            Log.w(TAG, "Kokoro model incomplete (voices.bin/espeak-ng-data/lexicon*.txt missing)")
            return null
        }
        val ruleFsts = candidateDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".fst") }
            ?.sortedBy { it.name }
            .orEmpty()
        return ModelFiles(
            candidateDir, onnx, tokens, lexicon, espeakData, dict, voicesBin,
            lexiconFiles, ruleFsts
        )
    }

    fun isModelReady(context: Context, variant: String): Boolean = try {
        findModelFiles(modelDir(context, variant), variant) != null
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to check tts model readiness", t)
        false
    }

    /** 清除全部已安装 TTS 模型并卸载引擎；返回是否成功 */
    fun clearInstalled(context: Context): Boolean = try {
        OfflineTtsManager.unload()
        modelRoot(context).deleteRecursively()
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to clear tts models", t)
        false
    }

    private fun findDirWithTokensAndOnnx(root: File): File? {
        val subs = root.listFiles()
        val hasOnnx = subs?.any { it.isFile && it.name.endsWith(".onnx") } == true
        if (File(root, "tokens.txt").isFile && hasOnnx) return root
        if (!root.isDirectory) return null
        subs?.forEach { sub ->
            if (sub.isDirectory && sub.name != "__MACOSX" && !sub.name.startsWith(".tmp_")) {
                findDirWithTokensAndOnnx(sub)?.let { return it }
            }
        }
        return null
    }
}
