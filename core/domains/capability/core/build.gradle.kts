// L0 功能域 capability · core：共用实现（领域逻辑 PlatformCapabilityExample + Feature 装配入口）。
// 只依赖本域 common-api（其自身只依赖 :core:kernel），不依赖任何 L1 模块（ADR-0030 / ADR-0028 D5）。
// 经 META-INF/services 声明 Feature，由 core-runtime 启动期自动发现——平台车道无需知道本域存在。
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

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // 域间依赖约束（不互依赖 / 无环）的机检落点：ArchUnit 只在测试期使用（ADR-0011）。
    testImplementation(libs.archunit.junit5)
}

tasks.test {
    useJUnitPlatform()
}
