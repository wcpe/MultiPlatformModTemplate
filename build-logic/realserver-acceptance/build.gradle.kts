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
        register("mpmtP3R7ReportGate") {
            id = "top.wcpe.mc.mpmt.p3-r7-report-gate"
            implementationClass =
                "top.wcpe.mc.mpmt.gradle.realserver.P3R7ReportGatePlugin"
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
}

tasks.named("check") {
    dependsOn("ktlintCheck", "detekt", "koverXmlReport")
}
