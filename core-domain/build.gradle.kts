// L0 core-domain：纯功能域，零平台依赖（ADR-0001）。
// 严格 Java 8：用 JDK 8 工具链编译——API 面即 JDK 8，从根上杜绝误用 Java 9+ API
// （比仅锁 sourceCompatibility 更强；满足 ADR-0004 / static-analysis 对 Java 8 的强制要求）。

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
    // 领域值对象用 Lombok 减样板（ADR-0004：Lombok 仅用于 Java 模块；编译期注解，运行期无依赖）。
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")

    // L0 不引入任何第三方运行期依赖（保持纯功能域）。
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
