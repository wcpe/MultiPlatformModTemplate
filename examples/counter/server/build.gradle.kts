// counter 域 · server：服务端实现
// 零 MC、Java 8；域外只允许依赖 :core:kernel（ADR-0030 / ADR-0028 决策 5）。
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
    api(project(":examples:counter:server-internal"))
    implementation(project(":examples:counter:core"))

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
