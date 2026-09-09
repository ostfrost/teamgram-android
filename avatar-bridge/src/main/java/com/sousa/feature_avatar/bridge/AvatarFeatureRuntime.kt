package com.sousa.feature_avatar.bridge

import android.app.Activity
import android.content.Context
import android.view.View
import kotlin.jvm.functions.Function0

internal object AvatarFeatureRuntime {
    private const val ENTRY_POINT = "com.sousa.feature_avatar.runtime.AvatarFeatureEntry"

    fun create(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback?
    ): View = invoke(
        "create",
        arrayOf(Context::class.java, Function0::class.java, TelegramStickerPackSyncCallback::class.java),
        context,
        onClose,
        stickerPackSyncCallback
    ) as View

    fun createWithHostCallbacks(
        context: Context,
        onClose: () -> Unit,
        stickerPackSyncCallback: TelegramStickerPackSyncCallback?,
        hostCallback: TelegramAvatarHostCallback,
        reactionTarget: TelegramAvatarReactionTarget?
    ): View = invoke(
        "createWithHostCallbacks",
        arrayOf(
            Context::class.java,
            Function0::class.java,
            TelegramStickerPackSyncCallback::class.java,
            TelegramAvatarHostCallback::class.java,
            TelegramAvatarReactionTarget::class.java
        ),
        context, onClose, stickerPackSyncCallback, hostCallback, reactionTarget
    ) as View

    fun sendReaction(
        context: Context,
        target: TelegramAvatarReactionTarget,
        reactionKind: String,
        callback: TelegramAvatarReactionSendCallback
    ) {
        invoke(
            "sendReaction",
            arrayOf(
                Context::class.java,
                TelegramAvatarReactionTarget::class.java,
                String::class.java,
                TelegramAvatarReactionSendCallback::class.java
            ),
            context,
            target,
            reactionKind,
            callback
        )
    }

    fun prewarm(activity: Activity) {
        invoke("prewarm", arrayOf(Activity::class.java), activity)
    }

    fun startParityServer(context: Context) {
        invoke("startParityServer", arrayOf(Context::class.java), context)
    }

    private fun invoke(name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? =
        try {
            Class.forName(ENTRY_POINT).getMethod(name, *parameterTypes).invoke(null, *args)
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Avatar runtime is unavailable. Ensure :avatar-module-android is packaged by the app.", error)
        }
}
