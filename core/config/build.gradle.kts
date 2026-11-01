// L1 core-config：客户端/服务端共享的平台无关配置加载（ADR-0010），把 YAML/JSON 加载为类型化模型。
// 严格 Java 8；只依赖 snakeyaml（YAML 是 JSON 的超集，两种格式共用同一 loader），源码用其原始包名，
// relocate 到 libs.* 是各平台 shade 期职责（ADR-0012）。
// 包名按 ADR-0030 之后的新约定 `top.wcpe.mc.mpmt.<层>.<模块>`，即 `top.wcpe.mc.mpmt.config`。

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

// 第三方依赖版本（snakeyaml 与各平台一致，避免分叉）
val snakeyamlVersion = "2.2"

dependencies {
    // 实现 kernel 的 ConfigPort 需要 L0 契约（方向正确：L1 → L0）
    api(project(":core:kernel"))
    // YAML 与 JSON 共用 snakeyaml（JSON 是 YAML 的子集），产物只背一个第三方库。
    // api 暴露：域拿到的是类型化模型，不需要直接引用 snakeyaml 类型，但传递依赖仍需可解析。
    api("org.yaml:snakeyaml:$snakeyamlVersion")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
