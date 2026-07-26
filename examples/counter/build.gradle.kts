// FR-18 上手示例域（非产品玩法）：纯 L0 逻辑，零平台依赖、可纯 JVM 单测。
// 不参与 :collectReleaseArtifacts 发布产物，仅作模板范本。

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
    // 只依赖 L0：示例必须证明"玩法逻辑不碰任何平台 API"
    api(project(":core:domain"))

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
