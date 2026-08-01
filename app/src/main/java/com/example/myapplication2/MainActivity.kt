package com.example.myapplication2

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.myapplication2.ui.theme.MyApplication2Theme

/**
 * App 一打開就會從這裡開始：顯示主畫面，並處理 USB 攝影機插入的通知。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleUsbAttachIntent(intent)
        enableEdgeToEdge()
        setContent {
            MyApplication2Theme {
                YoloCameraScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
    }

    private fun handleUsbAttachIntent(intent: Intent?) {
        // 如果是因為插入 USB 裝置而打開 App，就請畫面重新找一次攝影機。
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            UsbAttachEvents.notifyAttached()
        }
    }
}
