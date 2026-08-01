package com.example.myapplication2.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Material 3 色彩對照表，集中映射預覽頁專用調色盤。 */
private val CameraColorScheme = darkColorScheme(
    primary = CameraPrimary,
    onPrimary = CameraBackground,
    primaryContainer = CameraPrimaryVariant,
    onPrimaryContainer = CameraOnSurface,
    secondary = CameraSecondary,
    onSecondary = CameraBackground,
    background = CameraBackground,
    onBackground = CameraOnSurface,
    surface = CameraSurface,
    onSurface = CameraOnSurface,
    surfaceVariant = CameraSurfaceVariant,
    onSurfaceVariant = CameraOnSurfaceMuted,
    outline = CameraOutline
)

/**
 * 套用整個 App 的 Material 3 深色主題，並同步設定系統狀態列與導覽列顏色。
 * [content] 是需要繼承此主題的 Compose UI。
 */
@Composable
fun MyApplication2Theme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        // 預覽模式沒有真正 Activity；只在實際裝置執行時修改系統視窗列。
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = CameraBackground.toArgb()
            window.navigationBarColor = CameraBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    // 將色彩與字體透過 CompositionLocal 提供給所有子元件。
    MaterialTheme(
        colorScheme = CameraColorScheme,
        typography = Typography,
        content = content
    )
}
