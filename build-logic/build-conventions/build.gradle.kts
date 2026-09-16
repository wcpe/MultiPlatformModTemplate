// 构建约定插件工程：公共构建流程集中在此，平台车道脚本只做配置。
// 插件 id 采用功能语义、不含项目身份，见 docs/adr/0027-build-convention-plugins.md。
plugins {
    `kotlin-dsl`
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        register("quality") {
            id = "build-conventions.quality"
            implementationClass = "buildconventions.QualityConventionPlugin"
        }
        register("platformLane") {
            id = "build-conventions.platform"
            implementationClass = "buildconventions.PlatformLanePlugin"
        }
        register("fabricLane") {
            id = "build-conventions.fabric"
            implementationClass = "buildconventions.FabricLanePlugin"
        }
    }
}

dependencies {
    // 仅编译期引用 SpotBugs 类型：运行期由消费方根构建 `plugins { id("com.github.spotbugs") apply false }`
    // 提供同一份插件类路径，避免插件类加载器分裂。
    compileOnly("com.github.spotbugs.snom:spotbugs-gradle-plugin:6.0.26")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("ktlintCheck", "detekt")
}
