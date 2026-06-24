// L1 core-server：服务端公共逻辑（跨所有服务端平台一致的部分），平台无关，严格 Java 8。
// 当前承载握手服务端服务；经 protocol 收发（protocol 已 api 暴露 core-domain）。

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
    // 握手服务经 protocol 收发管线 + 版本协商；protocol 传递 core-domain（端口 / 状态机）
    api(project(":protocol"))
    // 服务端网络装配特性实现 core-runtime 的 Feature，经运行时编排（ARCHITECTURE §2.2：core-server → core-runtime）
    api(project(":core-runtime"))

    // 会话值对象用 Lombok（ADR-0004）
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
