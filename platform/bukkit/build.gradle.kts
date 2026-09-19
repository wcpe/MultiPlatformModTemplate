// platform/bukkit 聚合壳：物理 platform/bukkit/；产物 :platform:bukkit:<版本>:shadowJar

plugins {
    id("build-conventions.quality")
    java
}

// ktlint / detekt 的报告与规则制品需从 mavenCentral 解析（本壳工程无其它依赖来源）
repositories {
    mavenCentral()
}

// 壳工程无源码
sourceSets.named("main") {
    java.setSrcDirs(emptyList<Any>())
    resources.setSrcDirs(emptyList<Any>())
}
sourceSets.named("test") {
    java.setSrcDirs(emptyList<Any>())
    resources.setSrcDirs(emptyList<Any>())
}

tasks.named("jar") {
    enabled = false
}
tasks
    .matching { it.name.startsWith("compile") || it.name.startsWith("process") || it.name.startsWith("classes") }
    .configureEach { enabled = false }
tasks
    .matching {
        it.name.startsWith("checkstyle") ||
            it.name.startsWith("pmd") ||
            it.name.startsWith("spotbugs") ||
            it.name.startsWith("jacoco")
    }.configureEach { enabled = false }
tasks.matching { it.name == "test" || it.name == "check" }.configureEach { enabled = false }

tasks.register("verifyPackaging") {
    group = "verification"
    description = "聚合校验全部 Bukkit 版本产物"
    dependsOn(
        ":platform:bukkit:1.12.2:verifyPackaging",
        ":platform:bukkit:1.20.1:verifyPackaging",
        ":platform:bukkit:1.21.1:verifyPackaging",
        ":platform:bukkit:26.2:verifyPackaging",
    )
}

tasks.named("build") {
    dependsOn(
        ":platform:bukkit:bukkit-api:build",
        ":platform:bukkit:common:build",
        ":platform:bukkit:modern:build",
        ":platform:bukkit:1.12.2:build",
        ":platform:bukkit:1.20.1:build",
        ":platform:bukkit:1.21.1:build",
        ":platform:bukkit:26.2:build",
        "verifyPackaging",
    )
}
