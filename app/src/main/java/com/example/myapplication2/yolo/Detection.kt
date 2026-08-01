package com.example.myapplication2.yolo

import android.graphics.RectF

/**
 * 模型找到的一個物件。
 * 方框位置用 0 到 1 表示比例，所以不管螢幕大小都能畫在正確位置。
 */
data class Detection(
    val box: RectF,
    val score: Float,
    val classId: Int,
    val className: String,
    val group: Group,
    val labelZh: String
) {
    enum class Group {
        PERSON,
        VEHICLE,
        OBSTACLE,
        TRAFFIC
    }
}

