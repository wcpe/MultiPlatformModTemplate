// capability 域 · server-api：对外发布的服务端契约（抽象，不含实现）。
// 只依赖 common-api（其自身只依赖 :core:kernel）；零 MC、Java 8（ADR-0030 决策 1/5）。
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
    api(project(":core:domains:capability:common-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
