plugins {
    kotlin("jvm")
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "core"
        }
    }
}

kotlin {
    jvmToolchain(17)
    // ：`core` 一旦被 `api(:core)` 透传给宿主，它的 public 面就是事实上的公开契约。
    // 开 explicitApi 后每条 public 声明都必须自己写明可见性与返回类型，
    // 新增东西不会再「默认 public 悄悄漏出去」。
    explicitApi()
    // ADR-008：`core` 会被 Android AAR 消费，字节码目标与 Android 模块对齐到 11
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // ：契约层单独成模块，`:facade` 只把 core-api 暴露给消费者
    api(project(":core-api"))
    implementation(project(":core-proto"))
    testImplementation(kotlin("test"))
}

/**
 * 黄金向量来自仓库根的 `tests/golden/vectors.txt`（由 `tests/golden/gen.py` 生成）。
 *
 * **复制而不是引用绝对路径**：单测要能在任何机器上跑，包括把 `sdk/` 单独拷出去的场景。
 * 也**不复制进仓库**（不做第二份副本）—— 两份向量必然漂移。
 */
val goldenVectors = rootProject.layout.projectDirectory.file("../tests/golden/vectors.txt")

tasks.named<ProcessResources>("processTestResources") {
    from(goldenVectors) {
        into("golden")
        rename { "vectors.txt" }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    // 向量缺失时给出可操作的提示，而不是一句 "resource not found"
    doFirst {
        val f = goldenVectors.asFile
        if (!f.exists()) {
            throw GradleException(
                "缺少黄金向量 ${f.absolutePath} —— 请先运行：python tests/golden/gen.py",
            )
        }
    }
}
