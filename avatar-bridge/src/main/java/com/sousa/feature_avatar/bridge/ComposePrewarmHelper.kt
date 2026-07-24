package com.sousa.feature_avatar.bridge

import android.app.Activity

object ComposePrewarmHelper {
    @JvmStatic
    fun prewarm(activity: Activity) {
        AvatarFeatureRuntime.prewarm(activity)
    }
}