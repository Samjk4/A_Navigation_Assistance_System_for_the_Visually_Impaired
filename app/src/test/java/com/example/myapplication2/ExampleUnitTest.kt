package com.example.myapplication2

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
/** 在開發電腦 JVM 上執行的最小單元測試範例。 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        // 確認測試環境與 JUnit 設定可正常執行；尚未涵蓋 App 業務邏輯。
        assertEquals(4, 2 + 2)
    }
}
