// capability 域 · server-internal：服务端内部接口 + 内部 DTO（不放实现）。
// 依赖 server-api，供域内各模块按抽象协作，不触碰实现（ADR-0030 决策 1/5）。
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
    api(project(":core:domains:capability:server-api"))

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
