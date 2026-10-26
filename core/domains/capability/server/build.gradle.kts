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

    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
