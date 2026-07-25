// L1 core-paths：客户端/服务端共享的目录与资源路径预设（ADR-0010），平台无关，严格 Java 8。
// 基目录经 L0 DataDirectoryPort 由平台提供；本模块只在其下拼相对预设位置，不硬编码绝对路径。

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
    // 预设位置基于 L0 DataDirectoryPort 端口解析；api 暴露给调用方便于组合使用
    api(project(":core:domain"))

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
