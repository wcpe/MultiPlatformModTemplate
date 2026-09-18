// 真服验收编排约定插件：id = top.wcpe.mc.mpmt.realserver-acceptance / top.wcpe.mc.mpmt.realserver-report-gate。
// 不可变契约：插件 id、验收报告格式（SERVER-GAMETEST-REPORT v2）、-Pmpmt.acceptance.* 属性名与判定强度、
// 真服门禁的失败文案（docs/adr/0014-realserver-acceptance-harness.md）；编排一律经 Gradle 任务图，禁 shell 入口。
plugins {
    `kotlin-dsl`
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// JVM 固定 JDK 21，两侧一起锁：gradle/gradle-daemon-jvm.properties 决定跑本工程任务的守护进程
// （detekt 1.23 内嵌的 Kotlin 1.9 编译器在 JDK 25 上直接失败），工具链决定编译目标，使产物字节码
// 在「独立调用」与「被根构建 include」两条路径下一致；Gradle 9 要求守护 JVM ≥ 17，产物仍可被根构建加载。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

gradlePlugin {
    plugins {
        register("mpmtRealServerAcceptance") {
            id = "top.wcpe.mc.mpmt.realserver-acceptance"
            implementationClass =
                "top.wcpe.mc.mpmt.gradle.realserver.MpmtRealServerAcceptancePlugin"
        }
        register("mpmtRealServerReportGate") {
            id = "top.wcpe.mc.mpmt.realserver-report-gate"
            implementationClass =
                "top.wcpe.mc.mpmt.gradle.realserver.RealServerReportGatePlugin"
        }
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

detekt {
    baseline = file("config/detekt/baseline.xml")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 契约测试读取仓库外部文件（根构建 / 车道脚本 / 插件源码等）而未声明为任务输入；
    // 禁用 up-to-date 缓存，避免外部文件变更后仍命中陈旧的绿结果。
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn("ktlintCheck", "detekt", "koverXmlReport")
}
