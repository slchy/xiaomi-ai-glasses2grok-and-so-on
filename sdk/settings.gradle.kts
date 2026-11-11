pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        // 本机到 repo.maven.apache.org 的 TLS 会超时，Google 参考实现的 Maven Central 镜像可用；
        // 先查镜像，再回落到参考实现地址（不是代理，只是换仓库地址）。
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        google()
    }
}

rootProject.name = "miaiglasses-sdk"

include(":core-proto")
include(":core-api")
include(":core")
include(":transport")
include(":facade")
include(":sample")
include(":ktx")




