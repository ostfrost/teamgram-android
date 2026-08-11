package com.sousa.feature_avatar.bridge

import android.content.Context
import android.view.View

object AvatarExtension : TelegramExtensionPoint {
    override fun create(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback?
    ): View = AvatarFeatureRuntime.create(context, onClose, stickerPackSyncCallback)
}