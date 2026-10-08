package com.ai.assistance.operit.api.chat.llmprovider

import java.io.File

/**
 * HOSTCOMPAT-ADJACENT STUB (Operit port): the local llama.cpp engine was trimmed (SYNC.md §6);
 * this object keeps only the static model-directory helper that the settings screen renders.
 */
object LlamaProvider {
    fun getModelsDir(): File {
        val base = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        return File(base, "Operit/models/llama_cpp").apply { mkdirs() }
    }
}
