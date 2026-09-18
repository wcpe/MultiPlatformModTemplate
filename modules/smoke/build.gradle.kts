// smoke：架构验证载体（不发布，ARCHITECTURE §2.2）。当前承载跨端冒烟集成测试——
// 经进程内回环传输跑通"握手 + 版本协商 + 往返包"全链路（纯 JVM，无需真实平台 / 实机，FR-11 ② 的逻辑证明）。

plugins {
    id("build-conventions.quality")
    java
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
    // 集成测试同时驱动服务端与客户端公共逻辑（各自经 api 传递 protocol + core-domain）
    testImplementation(project(":core:server"))
    testImplementation(project(":core:client"))

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
