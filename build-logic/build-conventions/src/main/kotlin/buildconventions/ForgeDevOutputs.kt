package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

/** dev 输出任务名（run 任务与隐式依赖接线都按它取任务）。 */
internal const val FORGE_PREPARE_DEV_MOD_OUTPUTS_TASK = "prepareDevModOutputs"

/**
 * dev SecureJar 嵌入链：把共享 JAR 解压进 dev `classes` 目录，与 jar / acceptanceJar 的 shade 内容对齐。
 *
 * 共享 JAR 只在 packaging 时 shade，而 dev classpath 又不被 FML 模块层暴露给 mod，
 * 因此把产品/验收共享内容同步进对应 `classes` 目录，让 MOD_CLASSES 指向的 SecureJar 内自洽。
 *
 * 必须在任何消费 `main.output` / `acceptance.output` 的编译与分析任务之前完成，否则 Gradle 会报
 * implicit dependency（embed 写目录、编译任务读目录），全量 build 门禁失败。
 */
internal fun registerForgeDevModOutputs(project: Project, lane: ForgeLaneExtension) {
    val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
    val main = sourceSets.getByName("main")
    val acceptance = sourceSets.getByName("acceptance")
    val embedProduct =
        project.tasks.register("embedProductSharedIntoMain", Copy::class.java) {
            group = "build"
            description = "将产品共享 JAR 嵌入 main classes，供 dev SecureJar 发现 @Mod 与依赖类"
            dependsOn(project.tasks.named("classes"), project.tasks.named("processResources"))
            from(project.provider { project.configurations.getByName(FORGE_PRODUCT_BUNDLE).map { project.zipTree(it) } })
            into(main.java.destinationDirectory)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            lane.archiveExcludes.get().forEach { exclude(it) }
            exclude("top/wcpe/mc/mpmt/acceptance/**")
        }
    val embedAcceptance =
        project.tasks.register("embedAcceptanceSharedIntoAcceptance", Copy::class.java) {
            group = "build"
            description = "将验收共享 JAR 嵌入 acceptance classes，供 dev SecureJar 加载验收伴侣"
            dependsOn(project.tasks.named("acceptanceClasses"), project.tasks.named("processAcceptanceResources"))
            from(project.provider { project.configurations.getByName(FORGE_ACCEPTANCE_BUNDLE).map { project.zipTree(it) } })
            into(acceptance.java.destinationDirectory)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            lane.archiveExcludes.get().forEach { exclude(it) }
            lane.acceptanceExcludes.get().forEach { exclude(it) }
        }
    project.tasks.register(FORGE_PREPARE_DEV_MOD_OUTPUTS_TASK) {
        group = "build"
        description = "准备 loom run 任务所需的合并 SecureJar 目录"
        dependsOn(embedProduct, embedAcceptance)
    }
    wireForgeDevModOutputDependencies(project, embedProduct.name, embedAcceptance.name)
}

/** 读取 `main.output` / `acceptance.output` 的任务必须显式依赖对应的 embed 任务（Gradle 隐式依赖校验）。 */
private fun wireForgeDevModOutputDependencies(
    project: Project,
    embedProductTask: String,
    embedAcceptanceTask: String,
) {
    listOf("compileAcceptanceJava", "compileTestJava", "jar", "checkstyleMain", "pmdMain", "spotbugsMain").forEach { name ->
        project.tasks.named(name).configure { dependsOn(embedProductTask) }
    }
    listOf("checkstyleAcceptance", "pmdAcceptance", "spotbugsAcceptance").forEach { name ->
        project.tasks.named(name).configure { dependsOn(embedAcceptanceTask) }
    }
    listOf("compileContractTestJava", "test").forEach { name ->
        project.tasks.named(name).configure { dependsOn(FORGE_PREPARE_DEV_MOD_OUTPUTS_TASK) }
    }
    project.tasks.named(FORGE_ACCEPTANCE_JAR_TASK).configure { dependsOn(embedAcceptanceTask) }
}

/** 校验 dev SecureJar 目录内容完整（run 任务启动前兜底，缺 @Mod 入口或元数据即失败）。 */
fun verifyForgeDevSecureJarOutputs(project: Project, lane: ForgeLaneExtension) {
    val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
    val mainDir = sourceSets.getByName("main").java.destinationDirectory.get().asFile
    val acceptanceDir = sourceSets.getByName("acceptance").java.destinationDirectory.get().asFile
    val mainToml = File(mainDir, "META-INF/mods.toml")
    val acceptanceToml = File(acceptanceDir, "META-INF/mods.toml")
    val mainModClass = File(mainDir, lane.productModClass.get())
    val acceptanceModClass = File(acceptanceDir, lane.acceptanceModClass.get())
    val coreClass = File(mainDir, "top/wcpe/mc/mpmt/core/runtime/MpmtRuntime.class")
    if (!mainToml.isFile || !mainModClass.isFile || !coreClass.isFile) {
        throw GradleException("dev 产品 SecureJar 目录不完整：$mainDir")
    }
    if (!acceptanceToml.isFile || !acceptanceModClass.isFile) {
        throw GradleException("dev 验收 SecureJar 目录不完整：$acceptanceDir")
    }
}
