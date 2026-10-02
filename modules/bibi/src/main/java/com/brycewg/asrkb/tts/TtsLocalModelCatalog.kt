/**
 * 本地 TTS 模型目录：变体规格、安装目录约定、就绪检查与清除。
 *
 * 目录约定：getExternalFilesDir(null)/tts/<variant>/，与 ASR 本地模型
 * （sensevoice/<variant> 等）一致；模型包为 sherpa-onnx 官方 tts-models
 * 发布资产（tar.bz2）。
 *
 * 兼容两种打包布局（以 tokens.txt + *.onnx 为必备，其余可选）：
 * - lexicon 布局（vits-piper-zh_CN-xiao_ya* 等）：tokens.txt + lexicon.txt +
 *   规则 fst（phone/number/date.fst，经 OfflineTtsConfig.ruleFsts 传入），
 *   无 espeak-ng-data；
 * - espeak 布局（vits-piper-zh_CN-huayan 等旧 piper zh 包）：tokens.txt +
 *   espeak-ng-data/（+ dict/ jieba 词库）。
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

    data class TtsVariantSpec(
        val id: String,
        val labelRes: Int,
        val downloadUrl: String
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

    val variants: List<TtsVariantSpec> = listOf(VARIANT_MEDIUM, VARIANT_MEDIUM_INT8)

    fun defaultVariant(): String = VARIANT_MEDIUM.id

    fun normalizeVariant(id: String?): String =
        if (id == VARIANT_MEDIUM_INT8.id) VARIANT_MEDIUM_INT8.id else VARIANT_MEDIUM.id

    fun variantSpec(id: String?): TtsVariantSpec =
        variants.firstOrNull { it.id == normalizeVariant(id) } ?: VARIANT_MEDIUM

    fun modelRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODEL_ROOT_DIR)
    }

    fun modelDir(context: Context, variant: String): File =
        File(modelRoot(context), normalizeVariant(variant))

    /**
     * 解压后的模型文件组。除 dir/onnx 外均为可选（随打包布局而定）。
     */
    data class ModelFiles(
        val dir: File,
        val onnxFile: File,
        val tokensFile: File,
        val lexiconFile: File?,
        val espeakDataDir: File?,
        val dictDir: File?,
        /** 文本规范化规则 fst（phone/number/date 等），逗号拼接后传 ruleFsts */
        val ruleFstFiles: List<File>
    ) {
        fun ruleFstsArg(): String = ruleFstFiles.joinToString(",") { it.absolutePath }
    }

    /**
     * 在模型目录内定位文件组：找含 tokens.txt + 至少一个 .onnx 的目录（深度 ≤3）。
     * onnx 按 variant 偏好选取（int8 变体优先 *.int8.onnx，medium 优先非 int8）。
     */
    fun findModelFiles(dir: File, variant: String): ModelFiles? {
        if (!dir.exists()) return null
        val candidateDir = findDirWithTokensAndOnnx(dir) ?: return null
        val wantInt8 = normalizeVariant(variant) == VARIANT_MEDIUM_INT8.id
        val onnxFiles = candidateDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".onnx") }
            .orEmpty()
        if (onnxFiles.isEmpty()) return null
        val onnx = onnxFiles.firstOrNull { if (wantInt8) it.name.contains(".int8.") else !it.name.contains(".int8.") }
            ?: onnxFiles.first()
        val tokens = File(candidateDir, "tokens.txt")
        val lexicon = File(candidateDir, "lexicon.txt").takeIf { it.isFile }
        val espeakData = File(candidateDir, "espeak-ng-data").takeIf { it.isDirectory }
        val dict = File(candidateDir, "dict").takeIf { it.isDirectory }
        val ruleFsts = candidateDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".fst") }
            ?.sortedBy { it.name }
            .orEmpty()
        return ModelFiles(candidateDir, onnx, tokens, lexicon, espeakData, dict, ruleFsts)
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
