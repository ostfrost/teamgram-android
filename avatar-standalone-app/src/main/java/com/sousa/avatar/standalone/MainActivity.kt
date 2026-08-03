package com.sousa.avatar.standalone

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.sousa.feature_avatar.bridge.AvatarExtension

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            AvatarExtension.create(
                context = this,
                onClose = ::finish,
                stickerPackSyncCallback = null
            )
        )
    }
}
