package buildconventions

import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * mod 元数据的 `${version}` 占位由构建注入：产品与验收两套资源同口径（现代车道 `META-INF/mods.toml`，
 * 1.12.2 为 `mcmod.info`）。
 *
 * 1.12.2 的 `common/src/main/resources` 与 `src/main/resources` 存在同名条目，需 `EXCLUDE` 去重
 * （Gradle 9 默认对重复条目报错）。
 */
internal fun expandForgeModMetadata(project: Project, lane: ForgeLaneExtension) {
    listOf("processResources", "processAcceptanceResources").forEach { taskName ->
        project.tasks.named(taskName, ProcessResources::class.java).configure {
            if (lane.deduplicateProcessedResources.get()) {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
            inputs.property("version", project.version)
            filesMatching(lane.modMetadataResource.get()) { expand(mapOf("version" to project.version)) }
        }
    }
}

/** 全部 Java 编译任务统一 UTF-8 与 `-Xlint:all`；`options.release` 由各车道自行声明（版本目标即车道事实）。 */
internal fun configureForgeJavaCompilation(project: Project) {
    project.tasks.withType(JavaCompile::class.java).configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all")
    }
}

/**
 * 纯 JVM 单测：JUnit Platform + 车道目标 JDK 启动器。
 *
 * 现代车道的版本元数据经 `mpmt.test.*` 注入（与产品元数据同口径）；1.12.2 / 1.20.1 原样不注入。
 */
internal fun configureForgeUnitTests(project: Project, lane: ForgeLaneExtension) {
    val launcher = forgeJavaLauncher(project, lane.targetJavaVersion.get())
    project.tasks.withType(Test::class.java).configureEach {
        useJUnitPlatform()
        javaLauncher.set(launcher)
    }
    if (!lane.unitTestMetadata.get()) {
        return
    }
    project.tasks.named("test", Test::class.java).configure {
        systemProperty("mpmt.test.repositoryRoot", project.rootProject.projectDir.absolutePath)
        systemProperty("mpmt.test.projectDir", project.projectDir.absolutePath)
        systemProperty("mpmt.test.minecraftVersion", lane.mcVersion.get())
        systemProperty("mpmt.test.forgeVersion", lane.forgeVersion.get())
        systemProperty("mpmt.test.loomVersion", lane.loomVersion.get())
        systemProperty("mpmt.test.gradleVersion", project.gradle.gradleVersion)
    }
}
