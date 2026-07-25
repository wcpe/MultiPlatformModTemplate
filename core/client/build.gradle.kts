// L1 core-client：客户端公共逻辑（跨所有客户端加载器一致的部分），平台无关，严格 Java 8。
// 当前承载握手客户端服务；经 protocol 收发（protocol 已 api 暴露 core-domain）。不含具体渲染调用。

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
    api(project(":core:protocol"))
    // 客户端网络装配特性实现 core-runtime 的 Feature，经运行时编排（ARCHITECTURE §2.2：core-client → core-runtime）
    api(project(":core:runtime"))

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
