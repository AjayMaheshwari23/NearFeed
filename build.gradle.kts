buildscript {
    dependencies {
        // AGP 9 has built-in Kotlin. This pins the Kotlin toolchain used by the
        // Compose compiler plugin to a current compatible version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}
