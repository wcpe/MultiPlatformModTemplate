// realserver 验收 harness 平台无关核心（不发布，ADR-0014）：测试控制协议 + 客户端排程协调 + 单一权威报告。
// 独立于产品协议与平台——手写 codec、纯 JVM 逻辑；仅由各平台的 gametest 测试源集消费，不入产品 jar。
// 严格 Java 8（与核心一致，最大兼容；纯 JVM 测试设施无平台依赖）。

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
    // 值对象用 Lombok（仅编译期）；不依赖任何产品模块（保持测试协议与产品隔离，ADR-0014）
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
