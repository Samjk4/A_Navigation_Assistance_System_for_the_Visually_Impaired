package com.example.myapplication2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 把「有 USB 裝置插入」這件事通知畫面。
 * 用目前時間當通知內容，確保每插一次都會被當成新的事件。
 */
object UsbAttachEvents {
    private val _events = MutableStateFlow(0L)
    val events: StateFlow<Long> = _events.asStateFlow()

    fun notifyAttached() {
        _events.value = System.currentTimeMillis()
    }
}
