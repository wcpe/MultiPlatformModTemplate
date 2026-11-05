// L1 core-paths：客户端/服务端共享的平台无关路径预设（ADR-0010 决策 2）。
// 严格 Java 8；只依赖 L0 的 DataDirectoryPort，零第三方依赖、零平台依赖。
// 包名按 `top.wcpe.mc.mpmt.<层>.<模块>`：`top.wcpe.mc.mpmt.paths`。
//
// 为什么单独建模块而不并回 core-config：配置与路径是两件事——配置需要 snakeyaml，路径不需要。
// 并在一起会把 snakeyaml 带给只需要路径的调用方（尤其 L0 域），违背 ADR-0010 决策 1/2 的分工。

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
    // 只依赖 L0 契约（方向正确：L1 → L0），无第三方运行期依赖。
    api(project(":core:kernel"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
