// 构建约定插件工程：公共构建流程集中在此，平台车道脚本只做配置（docs/adr/0027-build-convention-plugins.md）。
// 不可变契约：插件 id 与实现类清单、各车道可见的任务名/属性名/报告路径；插件产物须可被根构建的守护进程加载。
plugins {
    `kotlin-dsl`
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// JVM 固定 JDK 21，两侧一起锁：
// · gradle/gradle-daemon-jvm.properties 决定跑本工程任务的守护进程（detekt 1.23 内嵌的 Kotlin 1.9 编译器
//   在 JDK 25 上直接抛 `IllegalArgumentException: 25.0.2`——IntelliJ 的 JavaVersion 解析器不认 25.x 版本串，
//   故 detekt 门禁只能跑在 ≤ 24 的 JVM 上；见 ADR-0027「插件工程自身也需要可用的 ktlint / detekt 门禁」）。
// · 工具链决定编译目标，使插件产物在「独立调用」与「被根构建 include」两条路径下字节码一致。
// Gradle 9 要求守护 JVM ≥ 17，产物仍可被根构建的 JDK 25 守护进程加载。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
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
        register("bukkitLane") {
            id = "build-conventions.bukkit"
            implementationClass = "buildconventions.BukkitLanePlugin"
        }
        register("spongeLane") {
            id = "build-conventions.sponge"
            implementationClass = "buildconventions.SpongeLanePlugin"
        }
        register("forgeLane") {
            id = "build-conventions.forge"
            implementationClass = "buildconventions.ForgeLanePlugin"
        }
        register("neoforgeLane") {
            id = "build-conventions.neoforge"
            implementationClass = "buildconventions.NeoForgeLanePlugin"
        }
        register("release") {
            id = "build-conventions.release"
            implementationClass = "buildconventions.ReleaseConventionPlugin"
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
