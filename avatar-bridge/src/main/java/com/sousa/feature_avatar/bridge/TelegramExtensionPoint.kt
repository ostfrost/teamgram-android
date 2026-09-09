package com.sousa.feature_avatar.bridge

import android.content.Context
import android.view.View

interface TelegramExtensionPoint {
    fun create(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback? = null
    ): View

    fun createWithHostCallbacks(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback? = null,
        hostCallback: TelegramAvatarHostCallback,
        reactionTarget: TelegramAvatarReactionTarget? = null
    ): View = create(context, onClose, stickerPackSyncCallback)

    fun sendReaction(
        context: Context,
        target: TelegramAvatarReactionTarget,
        reactionKind: String,
        callback: TelegramAvatarReactionSendCallback
    )
}
