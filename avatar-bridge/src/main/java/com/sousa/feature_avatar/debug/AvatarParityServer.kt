package com.sousa.feature_avatar.debug

import android.content.Context

object AvatarParityServer {
    @JvmStatic
    fun start(context: Context) {
        com.sousa.feature_avatar.bridge.AvatarFeatureRuntime.startParityServer(context)
    }
}