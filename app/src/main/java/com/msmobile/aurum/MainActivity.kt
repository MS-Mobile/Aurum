package com.msmobile.aurum

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.msmobile.aurum.camera.CameraScreen
import com.msmobile.aurum.tuner.TunerScreen
import com.msmobile.aurum.ui.theme.AurumTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AurumTheme(darkTheme = true) {
                var isTunerOpen by rememberSaveable { mutableStateOf(false) }
                // The tuner covers the camera instead of replacing it, so the camera stays bound for preview shots.
                Box {
                    CameraScreen(onOpenTuner = { isTunerOpen = true })
                    if (isTunerOpen) {
                        TunerScreen(onClose = { isTunerOpen = false })
                    }
                }
            }
        }
    }
}
