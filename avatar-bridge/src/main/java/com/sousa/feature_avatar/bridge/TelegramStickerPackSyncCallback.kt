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
    val animated: Boolean
)

fun interface TelegramStickerPackSyncCallback {
    fun syncTelegramStickerPack(request: TelegramStickerPackSyncRequest)
}
