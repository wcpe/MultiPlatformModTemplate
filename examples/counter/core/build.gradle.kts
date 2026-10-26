// counter 域 · core：共用实现：模型 / 算法 / 编解码（不含端差异）
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
    api(project(":examples:counter:common-api"))

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
