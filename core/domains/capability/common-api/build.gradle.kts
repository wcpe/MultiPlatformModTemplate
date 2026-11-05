// L0 功能域 capability · common-api：client / server 共用的对外契约（域事件）。
// 只依赖 :core:kernel；零平台依赖、Java 8（ADR-0030 / ADR-0028 决策 5）。
// 事件是跨域协作的唯一形态（ADR-0011），故放在对外可见的 api 模块。
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
    api(project(":core:kernel"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
