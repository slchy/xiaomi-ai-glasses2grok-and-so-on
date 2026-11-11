plugins {
    kotlin("jvm")
    //  发布闭合：`:core-api` / `:core` 的 POM 都引用它，不发布的话消费者解析直接失败
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "core-proto"
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

/**
 * 协议 plumbing：varint/hex/CRC/JSON 这些**两边都要用**的纯函数。
 *
 *  的取舍：它们既不属于公开契约（消费者不该 import），也不能真的 `internal`
 * （`:core-api` 与 `:core` 是不同模块，Kotlin 的 `internal` 跨不过去）。
 * 所以单独成一个模块，**只被 `implementation` 依赖** —— 消费者编译类路径上永远看不到它，
 * 类在这里可以是 public 而不污染公开面。
 */