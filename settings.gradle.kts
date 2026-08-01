// 控制 Gradle 插件下載來源，Android 相關插件只從 Google Maven 查找。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// 讓 Gradle 可依專案需求自動解析合適的 JDK toolchain。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// 集中管理所有 module 的一般依賴套件來源，避免個別 module 私自新增 repository。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// 根專案顯示名稱，以及唯一的 Android App module。
rootProject.name = "My Application2"
include(":app")
 
