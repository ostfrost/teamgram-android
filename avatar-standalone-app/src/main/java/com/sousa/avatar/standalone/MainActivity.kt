package com.sousa.avatar.standalone

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import com.sousa.feature_avatar.bridge.AvatarExtension

class MainActivity : ComponentActivity() {
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isDebuggable()) watchMainThread()

        setContentView(
            AvatarExtension.create(
                context = this,
                onClose = ::finish,
                stickerPackSyncCallback = null
            )
        )
    }

    override fun onDestroy() {
        frameMetricsListener?.let(window::removeOnFrameMetricsAvailableListener)
        frameMetricsListener = null
        super.onDestroy()
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Avatar engine work belongs on its render thread: make any regression onto main visible at once. */
    private fun watchMainThread() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectCustomSlowCalls()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
            if (totalMs > SLOW_FRAME_MS) {
                val inputAndLayoutMs = (metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                    metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                    metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)) / 1_000_000
                Log.w(TAG, "slow_main_frame total_ms=$totalMs input_anim_layout_ms=$inputAndLayoutMs")
            }
        }
        frameMetricsListener = listener
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
    }

    private companion object {
        const val TAG = "AvatarMainThread"
        const val SLOW_FRAME_MS = 16L
    }
}
