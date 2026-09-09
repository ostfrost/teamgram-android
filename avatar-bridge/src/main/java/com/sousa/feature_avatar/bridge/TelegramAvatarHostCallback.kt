package com.sousa.feature_avatar.bridge

enum class TelegramProfilePhotoMode {
    AVATAR_ONLY,
    WITH_REAL_PHOTO
}

data class TelegramProfilePhotoUpdateRequest(
    val avatarId: Long,
    val paramsHash: String,
    val mode: TelegramProfilePhotoMode,
    val avatarWebp: ByteArray,
    val layoutHint: String? = null
)

fun interface TelegramProfilePhotoUpdateCompletion {
    /** `true` only after Telegram has accepted and applied the new profile photo. */
    fun complete(applied: Boolean)
}

/**
 * Chat target the avatar feature reacts to. Supplied by the host when the feature is opened from a
 * conversation; absent when it is opened from settings, where the emotion tab stays a preview.
 */
data class TelegramAvatarReactionTarget(
    val conversationId: Long,
    /** `message` or `post`, matching the avatar-service reaction contract. */
    val targetType: String,
    val targetId: String
)

data class TelegramAvatarReactionDeliveryRequest(
    val conversationId: Long,
    val targetType: String,
    val targetId: String,
    val avatarId: Long,
    val reactionKind: String,
    val assetUrl: String,
    val stale: Boolean
)

interface TelegramAvatarReactionSendCallback {
    fun onSuccess(request: TelegramAvatarReactionDeliveryRequest)

    /**
     * A non-negative value is the remaining client/server cooldown. A negative value is a
     * non-throttling failure.
     */
    fun onFailure(retryAfterMs: Long)
}

/**
 * Host-owned integration points that must stay outside the avatar feature.
 *
 * WITH_REAL_PHOTO carries only the transparent avatar half. Teamgram obtains the
 * user's real photo and performs composition locally; real-photo bytes must never
 * be returned to the avatar module/backend.
 */
interface TelegramAvatarHostCallback {
    fun updateTelegramProfilePhoto(
        request: TelegramProfilePhotoUpdateRequest,
        completion: TelegramProfilePhotoUpdateCompletion
    )
    fun deliverTelegramAvatarReaction(request: TelegramAvatarReactionDeliveryRequest)
}
