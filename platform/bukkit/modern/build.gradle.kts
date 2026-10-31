// L3 platform-bukkit-modern：Folia / 现代 Paper 调度与 ModernBukkitVersionAdapter
// 仅 1.20+ server 工程依赖；不单独发布产品 jar。

plugins {
    id("build-conventions.quality")
    `java-library`
}

// group / version 由 settings.gradle.kts 的 gradle.lifecycle.beforeProject 统一下发；
// 此处不再回读根工程（rootProject.file 属于跨工程访问，Isolated Projects 会拒绝）。

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
