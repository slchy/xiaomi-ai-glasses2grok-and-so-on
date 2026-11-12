plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("android") version "1.9.24" apply false
    id("com.android.library") version "8.5.2" apply false
}

// 本地与导出的默认版本；打 tag 发版时用 -PmiaiglassesVersion=0.1.0 覆盖，
// 这样 release 里的 jar/aar 不会带着 -SNAPSHOT 后缀。
val releaseVersion: String = (findProperty("miaiglassesVersion") as String?) ?: "0.1.0-SNAPSHOT"

allprojects {
    group = "com.miaiglasses"
    version = releaseVersion
}
