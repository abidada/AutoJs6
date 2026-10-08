package com.ai.assistance.operit.util

import android.media.MediaMetadataRetriever

/**
 * Operit port: the FFmpegKit engine (upstream local AAR + smart-exception deps) is trimmed.
 * This shim keeps the same call surface ([executeCommand]/[getMediaInfo]) for upstream call
 * sites (StandardFileSystemTools media info) with a platform MediaMetadataRetriever backend.
 * Full transcoding/filtering is NOT available; see modules/operit/SYNC.md §6.
 */
object FFmpegUtil {
    private const val TAG = "FFmpegUtil"

    /** Mirrors the upstream FFmpegKit result object shape (format/duration/bitrate/streams). */
    data class MediaInformation(
        val format: String?,
        val duration: String?,
        val bitrate: String?,
        val streams: List<StreamInformation>?
    )

    data class StreamInformation(val type: String)

    /**
     * Build a scale filter string (kept for upstream call sites; filters are unavailable
     * without the engine — callers only format strings here).
     */
    fun scaleFilterMaxWidth(maxWidth: Int): String = "scale=min(${maxWidth}\\,iw):-2"

    /** Command execution is unavailable without the FFmpeg engine. */
    fun executeCommand(command: String): Boolean {
        AppLogger.w(TAG, "executeCommand unavailable: FFmpeg engine trimmed (command=$command)")
        return false
    }

    /** Platform-backed media info; null when the file cannot be probed. */
    fun getMediaInfo(filePath: String): MediaInformation? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val format = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val hasAudio = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            }.getOrNull()
            val hasVideo = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            }.getOrNull()
            val streams = buildList {
                if (hasVideo != null) add(StreamInformation("video"))
                if (hasAudio != null) add(StreamInformation("audio"))
            }
            MediaInformation(
                format = format,
                duration = duration,
                bitrate = bitrate,
                streams = streams.ifEmpty { null }
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting media info: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
