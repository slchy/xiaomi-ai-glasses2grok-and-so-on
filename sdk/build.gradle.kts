plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("android") version "1.9.24" apply false
    id("com.android.library") version "8.5.2" apply false
}

// 版本号的唯一来源是 gradle.properties 的 miaiglassesVersion：tag.yml 读它决定打哪个 tag。
// 打 tag 时 release.yml 再用 -PmiaiglassesVersion=<tag 去掉 v> 覆盖一次，保证产物与 tag 严格一致。
val releaseVersion: String = (findProperty("miaiglassesVersion") as String?) ?: "0.0.0-SNAPSHOT"

allprojects {
    group = "com.miaiglasses"
    version = releaseVersion
}
