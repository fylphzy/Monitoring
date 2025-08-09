// Top-level (project-level) build.gradle.kts

plugins {
    id("com.android.application") version "8.6.0" apply false
    id("com.android.library")     version "8.6.0" apply false
    kotlin("android")             version "1.9.10" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
