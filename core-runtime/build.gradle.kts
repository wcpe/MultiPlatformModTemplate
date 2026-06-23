// L1 core-runtime：框架编排（生命周期 / 特性注册 / 端口装配的「接收侧」），平台无关，严格 Java 8。
// 依赖方向：core-runtime → core-domain（ADR-0001）。它只「接收」被注入的端口实现，
// 不做平台发现（发现在 L2 platform-spi，注入进本运行时；L1 不依赖 L2）。

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
    // 运行时上下文对外暴露 core-domain 的 EventBusPort 等类型，故用 api 传递
    api(project(":core-domain"))

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
