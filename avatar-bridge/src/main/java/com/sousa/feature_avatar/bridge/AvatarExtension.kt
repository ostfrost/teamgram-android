package com.sousa.feature_avatar.bridge

import android.content.Context
import android.view.View

object AvatarExtension : TelegramExtensionPoint {
    override fun create(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback?
    ): View = AvatarFeatureRuntime.create(context, onClose, stickerPackSyncCallback)

    override fun createWithHostCallbacks(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback?,
        hostCallback: TelegramAvatarHostCallback,
        reactionTarget: TelegramAvatarReactionTarget?
    ): View = AvatarFeatureRuntime.createWithHostCallbacks(
        context, onClose, stickerPackSyncCallback, hostCallback, reactionTarget
    )

    override fun sendReaction(
        context: Context,
        target: TelegramAvatarReactionTarget,
        reactionKind: String,
        callback: TelegramAvatarReactionSendCallback
    ) = AvatarFeatureRuntime.sendReaction(context, target, reactionKind, callback)
}
