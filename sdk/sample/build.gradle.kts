plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.slchy.glasses2grok.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.slchy.glasses2grok.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = project.version.toString()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    testOptions {
        // 高清照的相位机是纯 Kotlin（不碰 Android），单测跑在纯 JVM 上
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":facade"))
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
