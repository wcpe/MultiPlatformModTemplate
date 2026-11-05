// counter 域 · server-internal：服务端内部接口 + 内部 DTO + 安装点
// 零 MC、Java 8；域外只允许依赖 :core:kernel（ADR-0030 / ADR-0028 决策 5）。
plugins {
    id("build-conventions.quality")
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
    api(project(":examples:counter:server-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
