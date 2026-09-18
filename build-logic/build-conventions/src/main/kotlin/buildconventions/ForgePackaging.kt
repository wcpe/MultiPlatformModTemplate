package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/** 验收 jar 任务名（发布、门禁与 dev SecureJar 接线都按它取任务）。 */
internal const val FORGE_ACCEPTANCE_JAR_TASK = "acceptanceJar"

/** 打包校验任务名。 */
private const val FORGE_VERIFY_PACKAGING_TASK = "verifyPackaging"

/** 打包出口任务名。 */
private const val FORGE_PACKAGE_ARTIFACTS_TASK = "packageArtifacts"

/** 产品 jar 打入的共享模块配置名（车道按此名声明并加依赖）。 */
internal const val FORGE_PRODUCT_BUNDLE = "productBundle"

/** 验收 jar 打入的共享模块配置名。 */
internal const val FORGE_ACCEPTANCE_BUNDLE = "acceptanceBundle"

/** 产品 jar 剔除的验收源集条目（验收内容只进验收伴侣）。 */
private const val FORGE_ACCEPTANCE_PACKAGE_PREFIX = "top/wcpe/mc/mpmt/acceptance/"

/** 现代 forge 车道（1.21.1 / 26.2）的平台包前缀：打包校验与 dev SecureJar 路径都按它定位。 */
internal const val FORGE_MODERN_PLATFORM_PACKAGE = "top/wcpe/mc/mpmt/platform/forge/modern/"

/**
 * 产品/验收 jar 均剔除的归档噪声：签名文件与（relocate 不改写的）Maven 元数据。
 *
 * 现代车道按需追加 `module-info.class`（多版本 jar 的模块描述符）；车道自身的薄包任务也可直接引用。
 */
val FORGE_ARCHIVE_EXCLUDES =
    listOf(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/MANIFEST.MF",
        "META-INF/maven/**",
    )

/** Java 字节码 major = 目标版本 + 44（Java 8 → 52、21 → 65、25 → 69）。 */
private const val FORGE_CLASS_MAJOR_OFFSET = 44

/**
 * 共享模块 jar 访问器：车道依赖块按 `ForgeModules.moduleJar(...)` 消费同根构建项目产物
 * （FileCollection，自带任务依赖），不再按 build/libs 路径硬编码。
 */
object ForgeModules {
    /** 按工程路径取该模块 jar 任务产物。 */
    fun moduleJar(project: Project, projectPath: String): FileCollection =
        project.files(project.project(projectPath).tasks.withType(Jar::class.java).matching { it.name == "jar" })
}

/**
 * 现代 forge 车道（1.21.1 / 26.2）的源集形状：common / server / client 分目录 + acceptance / contractTest。
 *
 * 必须在车道脚本的 `loom { runs { … } }` 与依赖声明之前完成（run 的 `source(acceptance)` 直接取源集对象）。
 *
 * `resources` 输出合并进 `classes` 目录：FML 会把每个源集的 resourcesDir 与 classesDirs 拼成两条 SecureJar
 * 路径，而 1.21 FML 对"仅有 mods.toml、无 @Mod 类"的路径直接 FATAL（constructed 0 mods）；
 * 合并后每 mod 只剩一条路径，共享 JAR 也随 `embed*` 落到同一目录，SecureJar 内自洽。
 */
fun registerForgeModernSourceSets(project: Project) {
    val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
    val main =
        sourceSets.getByName("main").apply {
            java.setSrcDirs(listOf("common/src/main/java", "server/src/main/java", "client/src/main/java"))
            resources.setSrcDirs(listOf("common/src/main/resources"))
        }
    // 验收源集 runtimeClasspath 只拼"输出 + main 输出 + main 运行期"：若再拼 compileClasspath，
    // compileOnly 共享 jar 会以自动模块名与 SecureJar 内嵌同名包冲突（split package）。
    val acceptance =
        sourceSets.create("acceptance") {
            java.setSrcDirs(listOf("src/acceptance/java"))
            resources.setSrcDirs(listOf("src/acceptance/resources"))
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath = output + main.output + main.runtimeClasspath
        }
    sourceSets.create("contractTest") {
        java.setSrcDirs(listOf("src/contractTest/java"))
        resources.setSrcDirs(listOf("src/contractTest/resources"))
        compileClasspath += main.output + acceptance.output + sourceSets.getByName("test").compileClasspath
        runtimeClasspath += output + compileClasspath
    }
    listOf(main, acceptance).forEach { sourceSet ->
        sourceSet.output.setResourcesDir(sourceSet.java.destinationDirectory.get().asFile)
    }
    // acceptance 编译需要 main 的 compileOnly 共享类；不 extends implementation（已无 runtime 共享 jar）
    project.configurations.getByName("acceptanceCompileOnly")
        .extendsFrom(project.configurations.getByName("compileOnly"))
    project.configurations.getByName("contractTestImplementation")
        .extendsFrom(project.configurations.getByName("testImplementation"))
    project.configurations.getByName("contractTestRuntimeOnly")
        .extendsFrom(project.configurations.getByName("testRuntimeOnly"))
}

/**
 * 现代 forge 车道的产品薄包：并入共享模块、剔除验收内容与归档噪声。
 *
 * 产品正式产物由 `remapJar`（1.21.1）或 `jar`（26.2 无混淆）以生产命名输出；本任务只负责内容装配。
 */
fun configureForgeModernProductJar(project: Project, lane: ForgeLaneExtension) {
    project.tasks.named("jar", Jar::class.java).configure {
        from(project.provider { project.configurations.getByName(FORGE_PRODUCT_BUNDLE).map { project.zipTree(it) } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        lane.archiveExcludes.get().forEach { exclude(it) }
        exclude("$FORGE_ACCEPTANCE_PACKAGE_PREFIX**")
        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to "MPMT ${lane.laneLabel.get()} 产品",
                    "Implementation-Version" to project.version,
                ),
            )
        }
    }
}

/**
 * 验收 jar：验收源集 + 验收共享模块，剔除产品内容与归档噪声。
 *
 * 产物名与 manifest 标题由车道给出（发布契约不可变）；1.12.2 的 FG 时代布局额外要求带版本号并落 `devlibs`
 * （正式产物由随后的 `remapAcceptanceJar` 输出到 `libs`）。
 */
fun registerForgeAcceptanceJar(
    project: Project,
    lane: ForgeLaneExtension,
) = project.tasks.register(FORGE_ACCEPTANCE_JAR_TASK, Jar::class.java) {
    group = "build"
    description =
        if (lane.acceptanceJarVersionedDevLibs.get()) {
            "构建独立 ${lane.laneLabel.get()} 验收伴侣 JAR（dev 命名中间产物）"
        } else {
            "构建独立 ${lane.laneLabel.get()} 验收 mod JAR"
        }
    archiveBaseName.set(lane.acceptanceJarName)
    archiveClassifier.set("")
    if (lane.acceptanceJarVersionedDevLibs.get()) {
        archiveVersion.set(project.version.toString())
        destinationDirectory.set(project.layout.buildDirectory.dir("devlibs"))
    }
    from(project.extensions.getByType(SourceSetContainer::class.java).getByName("acceptance").output)
    from(project.provider { project.configurations.getByName(FORGE_ACCEPTANCE_BUNDLE).map { project.zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    lane.archiveExcludes.get().forEach { exclude(it) }
    lane.acceptanceExcludes.get().forEach { exclude(it) }
    manifest {
        attributes(
            mapOf(
                "Implementation-Title" to lane.acceptanceJarTitle.get(),
                "Implementation-Version" to project.version,
            ),
        )
    }
}

/**
 * 验收伴侣 remap 任务（`remapAcceptanceJar`，named → SRG）：1.12.2 与 1.20.1 的生产命名验收 jar。
 *
 * 两车道的差异只在归档名与 classpath（1.12.2 恒带版本并拼 acceptance 源集运行期，
 * 1.20.1 用固定 `-dev-shadow` 输入产物、归档名 `mpmt-acceptance-forge`）：产物名、版本、
 * 分类器与 CC 防捕获写法与迁移前逐项一致。
 *
 * loom 的 `RemapJarTask` 类型不在插件工程编译类路径内，故按 `remapJar` 任务的类
 * （与 loom 插件同类加载器，未装饰的原始类型）注册新任务；`inputFile` / `classpath`
 * 经 `get*` 取回属性对象后设值（Gradle 装饰对象不认 Groovy 属性名直接调用）。
 * 注册时机要求 loom 插件已注册 `remapJar`（即车道脚本 `loom { }` 块之后调用，
 * 故本函数只在 `afterEvaluate` 内执行）。
 */
internal fun registerForgeAcceptanceRemapJar(
    project: Project,
    lane: ForgeLaneExtension,
) {
    val baseName = lane.remapAcceptanceJarName.get()
    val withVersion = lane.remapAcceptanceJarVersioned.get()
    val remapSource = project.tasks.named("remapJar").get()
    val remapType = remapSource.javaClass.superclass as Class<out org.gradle.api.Task>
    val acceptanceJar = project.tasks.named(FORGE_ACCEPTANCE_JAR_TASK)
    project.tasks.register("remapAcceptanceJar", remapType) {
        group = "build"
        description =
            if (withVersion) {
                "将验收伴侣重映射到生产命名（srg），等价原 FG reobfAcceptanceJar"
            } else {
                "把验收 mod jar remap 到 SRG（arch-loom 承担 FG reobf）"
            }
        dependsOn(acceptanceJar)
        (groovyValue("getInputFile") as RegularFileProperty)
            .set(acceptanceJar.flatMap { (it as AbstractArchiveTask).archiveFile })
        if (withVersion) {
            // 仅 1.12.2 拼 acceptance 源集运行期 classpath；1.20.1 的 shadow 链路不拼。
            // modern 车道不走 remap 分支，无影响。
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            (groovyValue("getClasspath") as ConfigurableFileCollection)
                .setFrom(sourceSets.getByName("acceptance").runtimeClasspath)
        }
        (this as AbstractArchiveTask).archiveBaseName.set(baseName)
        if (withVersion) {
            (this as AbstractArchiveTask).archiveVersion.set(project.version.toString())
        }
        (this as AbstractArchiveTask).archiveClassifier.set("")
    }
}

/**
 * FG 时代 reobf 产物路径兼容层（根文件契约不可断）：
 *
 * 根 `:collectReleaseArtifacts` 按 `build/reobfJar/output.jar` / `build/reobfShadowJar/output.jar` 收集发布制品，
 * 根 realserver 说明沿用 `gradlew reobfJar reobfAcceptanceJar` 命令与产物路径；arch-loom 下重映射由
 * `remapJar` / `remapAcceptanceJar` 产出，这里只做落位。
 */
internal fun registerForgeReobfCopyCompat(project: Project, lane: ForgeLaneExtension) {
    lane.reobfCopyTasks.get().forEach { (copyTaskName, sourceTaskName) ->
        val source = project.tasks.named(sourceTaskName, AbstractArchiveTask::class.java)
        project.tasks.register(copyTaskName, Copy::class.java) {
            group = "build"
            description = "同步 $sourceTaskName 产物到 build/$copyTaskName/output.jar（FG 时代既有路径）"
            dependsOn(source)
            from(source.flatMap { it.archiveFile })
            into(project.layout.buildDirectory.dir(copyTaskName))
            rename { "output.jar" }
        }
    }
}

/** 读 class 文件的 major 版本（打包校验断言字节码目标用）。 */
internal fun forgeClassMajor(
    jar: File,
    entry: String,
): Int {
    ZipFile(jar).use { zip ->
        val input = DataInputStream(zip.getInputStream(zip.getEntry(entry)))
        try {
            if (input.readInt() != 0xCAFEBABE.toInt()) {
                throw GradleException("类文件魔数错误：$entry")
            }
            input.readUnsignedShort()
            return input.readUnsignedShort()
        } finally {
            input.close()
        }
    }
}

/**
 * 现代 forge 车道的打包校验：产品与验收 JAR 隔离、入口、共享核心与平台字节码目标。
 *
 * 断言清单与失败文案与迁移前车道脚本逐条一致（仅入口类名与 class major 由车道参数派生）。
 */
internal fun registerForgeModernPackagingVerification(project: Project, lane: ForgeLaneExtension) {
    val productTask = project.tasks.named(lane.productTaskName.get(), AbstractArchiveTask::class.java)
    val acceptanceTask = project.tasks.named(FORGE_ACCEPTANCE_JAR_TASK, AbstractArchiveTask::class.java)
    val javaVersion = lane.targetJavaVersion.get()
    val classMajor = javaVersion + FORGE_CLASS_MAJOR_OFFSET
    val productModClass = lane.productModClass.get()
    val acceptanceModClass = lane.acceptanceModClass.get()
    val acceptancePlatformPackage = acceptanceModClass.substringBeforeLast('/') + "/"
    project.tasks.register(FORGE_VERIFY_PACKAGING_TASK) {
        group = "verification"
        description = "校验产品与验收 JAR 隔离、入口、共享核心和 Java $javaVersion 平台字节码"
        dependsOn(productTask, acceptanceTask)
        packagingVerification(
            laneLabel = lane.laneLabel.get(),
            mcVersion = lane.mcVersion.get(),
            product = productTask.flatMap { it.archiveFile },
            acceptance = acceptanceTask.flatMap { it.archiveFile },
        ) { product, acceptance ->
            val acceptanceJar = requireNotNull(acceptance) { "缺少验收 jar 输入" }
            mustContain(product, productModClass, "产品缺少 mod 入口")
            mustContain(product, "top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class", "产品未 shade core-server")
            mustContain(product, "top/wcpe/mc/mpmt/core/client/ClientNetworkFeature.class", "产品未 shade core-client")
            mustContain(product, "top/wcpe/mc/mpmt/protocol/PacketCodec.class", "产品未 shade protocol")
            mustNotBundle(product, listOf(FORGE_ACCEPTANCE_PACKAGE_PREFIX), "产品误包含 acceptance 核心")
            mustContain(acceptanceJar, acceptanceModClass, "验收 JAR 缺少入口")
            mustContain(
                acceptanceJar,
                "top/wcpe/mc/mpmt/acceptance/report/AcceptanceReportV2Factory.class",
                "验收 JAR 未包含 acceptance 核心",
            )
            mustNotBundle(acceptanceJar, listOf("top/wcpe/mc/mpmt/core/"), "验收 JAR 重复包含 core")
            mustNotBundle(acceptanceJar, listOf("top/wcpe/mc/mpmt/protocol/"), "验收 JAR 重复包含 protocol")
            mustNotContain(acceptanceJar, productModClass, "验收 JAR 重复包含产品入口")
            product.entries
                .filter { it.startsWith(FORGE_MODERN_PLATFORM_PACKAGE) && it.endsWith(".class") }
                .forEach { entry ->
                    must(forgeClassMajor(product.file, entry) == classMajor, "产品平台类不是 Java $javaVersion：$entry")
                }
            acceptanceJar.entries
                .filter { it.startsWith(acceptancePlatformPackage) && it.endsWith(".class") }
                .forEach { entry ->
                    must(
                        forgeClassMajor(acceptanceJar.file, entry) == classMajor,
                        "验收平台类不是 Java $javaVersion：$entry",
                    )
                }
        }
    }
}

/** 打包并校验入口：额外依赖由车道给出（26.2 的静态质量门清单）。 */
internal fun registerForgePackageArtifacts(project: Project, lane: ForgeLaneExtension) {
    project.tasks.register(FORGE_PACKAGE_ARTIFACTS_TASK) {
        group = "build"
        description = "打包并校验 ${lane.laneLabel.get()} 产品与验收 JAR"
        dependsOn(FORGE_VERIFY_PACKAGING_TASK)
        lane.packageArtifactsDependsOn.get().forEach { dependsOn(it) }
    }
}
