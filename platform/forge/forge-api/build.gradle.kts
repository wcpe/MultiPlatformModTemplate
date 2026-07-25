// Forge 平台对外 API：平台胶水对外契约（L3-api），无具体 loader 实现。
// 版本工程 / 玩法扩展依赖本模块；实现放在 common 或版本工程内。
// 依赖：platform-spi（装配）+ core-server（服务端公共）。

base {
    archivesName.set("platform-forge-api")
}

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core:spi"))
    api(project(":core:server"))
    api(project(":core:client"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}
