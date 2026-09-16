package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.nio.charset.StandardCharsets

/**
 * 平台车道公共基座：车道脚本 `plugins { id("build-conventions.platform") }` 后只做配置。
 *
 * 目前承担：
 * - 验收控制通道常量生成（`generateAcceptanceChannelId`）：通道名由构建期注入到生成源码，
 *   使产品端与验收端共用同一常量，避免两处手写漂移。
 */
class PlatformLanePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val lane = project.extensions.create("platformLane", PlatformLaneExtension::class.java)
        lane.acceptanceSourceSetName.convention("acceptance")
        lane.generatedSourcesDir.convention(
            project.layout.buildDirectory.dir("generated/sources/acceptance/java"),
        )
        registerAcceptanceChannelId(project, lane)
    }

    /**
     * 生成验收控制通道常量。
     *
     * 生成内容与会话前各车道手写实现逐字节一致（含空行与尾部换行），
     * 因此验收 jar 的字节不因本次抽取而改变。
     */
    private fun registerAcceptanceChannelId(project: Project, lane: PlatformLaneExtension) {
        val task =
            project.tasks.register("generateAcceptanceChannelId") {
                group = "build"
                inputs.property("channelName", lane.channelName)
                inputs.property("channelPackage", lane.channelPackage)
                inputs.property("channelClass", lane.channelClass)
                inputs.property("mcVersion", lane.mcVersion)
                outputs.dir(lane.generatedSourcesDir)
                doLast {
                    val packageName = lane.channelPackage.get()
                    val className = lane.channelClass.get()
                    val packageDir = lane.generatedSourcesDir.get().asFile.resolve(packageName.replace('.', '/'))
                    packageDir.mkdirs()
                    packageDir.resolve("$className.java").writeText(
                        """
                        package $packageName;

                        /** 构建期生成的验收控制通道（MC ${lane.mcVersion.get()}）。 */
                        public final class $className {

                            /** 当前目标版本的验收控制通道。 */
                            public static final String CHANNEL = "${lane.channelName.get()}";

                            private $className() {
                            }
                        }
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )
                }
            }

        project.afterEvaluate {
            // 车道配置块执行后才能取到版本值（插件 apply 早于它）。
            tasks.named("generateAcceptanceChannelId").configure {
                description = "生成验收控制通道常量（MC ${lane.mcVersion.get()}）"
            }
            val sourceSets = extensions.getByType(SourceSetContainer::class.java)
            val acceptance: SourceSet = sourceSets.getByName(lane.acceptanceSourceSetName.get())
            acceptance.java.srcDir(lane.generatedSourcesDir)
            tasks.named(acceptance.compileJavaTaskName).configure {
                dependsOn(task)
            }
            // java 扩展在聚合壳工程可能不存在；这里只做校验，确保车道确实创建了源集。
            extensions.findByType(JavaPluginExtension::class.java)
        }
    }
}
