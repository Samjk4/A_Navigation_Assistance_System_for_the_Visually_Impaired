// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 根專案只宣告插件版本；實際套用由各 module 的 build.gradle.kts 負責。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
