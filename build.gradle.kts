// Plugins live on the root classpath so Kotlin and AGP share one classloader.
// AGP is only added when the Android module is part of the build (pass -PcoreOnly=true to skip it on
// machines without the Android SDK / Google Maven access).
buildscript {
    val coreOnly = providers.gradleProperty("coreOnly").orNull == "true"
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
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (!coreOnly) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
        }
    }
}
