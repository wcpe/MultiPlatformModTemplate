// L1 protocol：跨端协议单一真源（包定义 / 字节布局 / 版本协商 / 编解码），平台无关，严格 Java 8。
// 依架构 protocol → core-domain（ADR-0001）；M2 编解码自包含、暂未引用 core-domain 类型，
// 待传输 / 握手领域接入时再加该依赖（scope-discipline：用到才建）。

plugins {
    `java-library`
}

java {
    // 与 L0 一致：JDK 8 工具链，杜绝误用 Java 9+ API（ADR-0004）
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // 值对象（协议包 DTO）用 Lombok 减样板（ADR-0004：Lombok 仅用于 Java 模块）
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
