// 真服验收编排约定插件：id = top.wcpe.mc.mpmt.realserver-acceptance
// 目标：用 Gradle BuildService / 任务图取代 scripts/*.sh 编排（用户硬约束：禁 sh 入口）。
plugins {
    `kotlin-dsl`
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
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
    // 契约测试读取仓库外部文件（根构建 / 各车道 settings 等）而未声明为任务输入；
    // 禁用 up-to-date 缓存，避免外部文件变更后仍命中陈旧的绿结果。
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn("ktlintCheck", "detekt", "koverXmlReport")
}
