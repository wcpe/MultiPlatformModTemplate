// L3 platform-bukkit-common：全版本共用胶水 + version-api（JDK8 + Spigot 1.12 API 编译边界）
// 不单独发布产品 jar；由各 server-* 工程 shadow 进版本产物。

plugins {
    `java-library`
}

group = "top.wcpe.mc.mpmt"
version = rootProject.file("VERSION").readText().trim()

val snakeyamlVersion = "2.2"
val spigot112Api = "org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

repositories {
    mavenCentral()
    // Spigot 快照（含 spigot-api 及其传递的 bungeecord-chat 等）
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "Spigot"
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "SonatypeSnapshots"
    }
    // 旧版 bungeecord-chat 偶发仅在此
    maven("https://repo.md-5.net/content/repositories/snapshots/") {
        name = "md5Snapshots"
    }
}

dependencies {
    // 公共 L3 只看 1.12 API，防止现代类型泄漏进 Java 8 字节码
    // bungeecord-chat 1.12-SNAPSHOT 已从公网下架，用仓内 third-party 补齐传递依赖
    compileOnly(spigot112Api) {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }
    compileOnly(files(rootProject.file("platform/bukkit/third-party/bungeecord-chat-1.12-SNAPSHOT.jar")))
    api(project(":platform:bukkit:bukkit-api"))
    api(project(":core:spi"))
    api(project(":core:server"))
    api("org.yaml:snakeyaml:$snakeyamlVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}
