package com.sousa.feature_avatar.bridge

data class TelegramStickerPackSyncRequest(
    val avatarId: Long,
    val paramsHash: String,
    val title: String,
    val shortName: String,
    val units: List<TelegramStickerPackUnit>
)

data class TelegramStickerPackUnit(
    val unitId: String,
    val emotionId: String,
    val filePath: String,
    val mimeType: String,
    val animated: Boolean,
    /**
     * Transparent still frame of this emotion, always present locally.
     *
     * An animated unit ships as a webm, which Telegram refuses as a custom emoji at 512px and whose
     * alpha channel is lost when a frame is extracted from it. The host uses this instead so the
     * static fallback keeps its transparency. Null only from a module build that predates it.
     */
    val staticFramePath: String? = null
)

fun interface TelegramStickerPackSyncCallback {
    fun syncTelegramStickerPack(request: TelegramStickerPackSyncRequest)
}
