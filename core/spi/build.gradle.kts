// L2 platform-spi：平台抽象层——SPI 接口 + PlatformProvider(Holder) + ServiceLoader 发现 + FeatureGate。
// 依赖方向：platform-spi → core-runtime → core-domain（L2 → L1 → L0，ADR-0001）。
// 它负责「发现并装配」：发现唯一活跃平台、把端口注入 L1 运行时；L1 不反向依赖本层。

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
    // 装配时把端口注入 core-runtime 的 RuntimePorts；core-runtime 已 api 暴露 core-domain
    api(project(":core:runtime"))

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
