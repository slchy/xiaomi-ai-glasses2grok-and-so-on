plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "io.github.slchy.glasses2grok.sdk.ktx"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    publishing {
        singleVariant("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    explicitApi()
}

dependencies {
    // 主 AAR 不引 coroutines；只有这个模块引。
    // 必须是 `api`：公开 API 直接返回 `Flow`，宿主编译期就需要 coroutines 在 classpath 上
    // （实测：写成 implementation 时宿主报 "Cannot access class kotlinx.coroutines.flow.Flow"）。
    api(project(":facade"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "sdk-ktx"
            }
        }
    }
}
