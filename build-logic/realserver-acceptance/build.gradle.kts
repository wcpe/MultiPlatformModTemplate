// 真服验收编排约定插件：id = top.wcpe.mc.mpmt.realserver-acceptance
// 目标：用 Gradle BuildService / 任务图取代 scripts/*.sh 编排（用户硬约束：禁 sh 入口）。
plugins {
    `kotlin-dsl`
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
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
