plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.miaiglasses.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miaiglasses.sample"
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
}

dependencies {
    implementation(project(":facade"))
}
