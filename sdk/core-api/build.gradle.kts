plugins {
    kotlin("jvm")
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "core-api"
        }
    }
}

kotlin {
    jvmToolchain(17)
    // 设计记录：与 `:core` / Android 模块的字节码目标一致
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }

    /**
     * ：这里是 `:facade` 公开签名真正依赖的那一层（事件、状态、DTO、协议常量）。
     * 它在消费者的编译类路径上，所以每条声明都必须是深思熟虑的公开面 —— 与 `:core` 同样开 explicitApi。
     */
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(project(":core-proto"))
    testImplementation(kotlin("test"))
}
