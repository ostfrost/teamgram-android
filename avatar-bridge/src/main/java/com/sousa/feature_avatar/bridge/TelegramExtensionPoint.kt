package com.sousa.feature_avatar.bridge

import android.content.Context
import android.view.View

interface TelegramExtensionPoint {
    fun create(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback? = null
    ): View

    fun createGiftClaim(
        context: Context,
        token: String,
        onClose: () -> Unit
    ): View
}