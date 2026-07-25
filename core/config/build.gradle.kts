// L1 core-config：客户端/服务端共享的平台无关配置加载（ADR-0010），把 YAML/JSON 加载为类型化模型。
// 严格 Java 8；依赖 snakeyaml（YAML）+ gson（JSON），源码用其原始包名，relocate 到 libs.* 是各平台 shade 期职责（ADR-0012）。

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

// 第三方依赖版本（snakeyaml 与各平台一致，避免分叉）
val snakeyamlVersion = "2.2"
val gsonVersion = "2.10.1"

dependencies {
    // 配置加载运行期依赖：api 暴露，便于调用方按需直接使用其类型化结果
    api("org.yaml:snakeyaml:$snakeyamlVersion")
    api("com.google.code.gson:gson:$gsonVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
