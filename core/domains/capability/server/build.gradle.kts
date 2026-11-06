// capability 域 · server：服务端实现（实现 server-internal 的抽象 + Feature 装配入口）。
// 依赖 server-internal（抽象）与 core（共用逻辑）；不得依赖 client（ADR-0030 决策 5）。
// 经 META-INF/services 声明 Feature，由 core-runtime 启动期自动发现。
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
    api(project(":core:domains:capability:server-internal"))
    implementation(project(":core:domains:capability:core"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
