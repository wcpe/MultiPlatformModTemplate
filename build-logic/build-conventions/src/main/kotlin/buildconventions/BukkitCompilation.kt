package buildconventions

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

/** Java 8 车道的 source/targetCompatibility 取值（与原脚本逐字一致；Java 8 工具链无 `--release`）。 */
private const val BUKKIT_JAVA8_COMPATIBILITY = "1.8"

/** 源文件与产物统一 UTF-8。 */
private const val BUKKIT_SOURCE_ENCODING = "UTF-8"

/**
 * 编译工具链与编译任务属性。
 *
 * 工具链用 `compilerJavaVersion` 取编译器（26.2 要 25 才能解析 paper-api 的 class major）；
 * 字节码目标由 `options.release` 表达，Java 8 工具链不支持 `--release`，改用 source/targetCompatibility。
 */
internal fun configureBukkitJavaCompilation(project: Project, lane: BukkitLaneExtension) {
    val compilerJavaVersion = JavaLanguageVersion.of(lane.compilerJavaVersion.get())
    project.extensions.getByType(JavaPluginExtension::class.java)
        .toolchain.languageVersion.set(compilerJavaVersion)
    val compiler =
        project.extensions.getByType(JavaToolchainService::class.java)
            .compilerFor { languageVersion.set(compilerJavaVersion) }
    project.tasks.withType(JavaCompile::class.java).configureEach {
        javaCompiler.set(compiler)
        options.encoding = BUKKIT_SOURCE_ENCODING
        if (lane.releaseTargetVersion.get()) {
            options.release.set(lane.targetJavaVersion.get())
        } else {
            sourceCompatibility = BUKKIT_JAVA8_COMPATIBILITY
            targetCompatibility = BUKKIT_JAVA8_COMPATIBILITY
        }
    }
}
