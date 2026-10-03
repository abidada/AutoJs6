/**
 * TTS 服务商枚举与注册表。
 *
 * 与 AsrVendor/AsrVendorRegistry 同构：当前仅本地离线 sherpa-onnx 一家，
 * 在线 TTS 供应商（火山/MiniMax 等）后续作为新枚举项 + 新 TtsEngine 实现接入，
 * 编排层（TtsPlaybackCoordinator）与设置 UI 不需要结构性改动。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import androidx.annotation.StringRes
import com.brycewg.asrkb.R

enum class TtsVendor(val id: String, @param:StringRes val displayNameResId: Int) {
    /** 本地离线合成（sherpa-onnx OfflineTts，vits-piper 系列） */
    SherpaOffline("sherpa_offline", R.string.tts_vendor_sherpa_offline),

    /** CloneTTS 本地/局域网 HTTP 服务（同机 127.0.0.1 或局域网地址，克隆音色） */
    CloneTts("clonetts", R.string.tts_vendor_clonetts);

    companion object {
        fun fromId(id: String?): TtsVendor =
            entries.firstOrNull { it.id == id } ?: SherpaOffline

        fun ordered(): List<TtsVendor> = entries.toList()
    }
}
