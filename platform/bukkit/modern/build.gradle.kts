// L3 platform-bukkit-modern：Folia / 现代 Paper 调度与 ModernBukkitVersionAdapter
// 仅 1.20+ server 工程依赖；不单独发布产品 jar。

plugins {
    `java-library`
}

group = "top.wcpe.mc.mpmt"
version = rootProject.file("VERSION").readText().trim()

// modern 公共层用 1.20.1 paper-api 编译；1.21 server 再叠自己的 API
val paperApi = "io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
}

dependencies {
    api(project(":platform:bukkit:common"))
    compileOnly(paperApi)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
