package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

/**
 * 冻结编译期 API：用独立可解析配置拿到 API jar，并核对其 SHA-256。
 *
 * 语义与原各车道内联实现一致：配置名 `apiVerification`、任务名 `verifyApiSnapshotFreeze`
 * （group=verification）、通过 / 失败文案与 `compileJava` 接线保持不变；
 * 这样 Gradle 任务图与构建输出都不因抽取而改变。
 */
fun Project.frozenApiSnapshot(
    laneLabel: String,
    coordinate: String,
    expectedSha256: String,
    mcVersion: String,
    acceptanceSourceSetName: String = "acceptance",
) {
    val apiVerification =
        configurations.create("apiVerification") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
        }
    dependencies.add(apiVerification.name, coordinate)

    val verifyTask =
        tasks.register("verifyApiSnapshotFreeze") {
            group = "verification"
            description = "验证 $laneLabel $mcVersion API JAR 与冻结 SHA-256 一致"
            // 配置期把可解析配置包成 FileCollection：动作闭包只捕获该集合，不捕获 Configuration
            // （Configuration 不可配置缓存序列化）；解析时机仍在任务执行期，判定语义不变。
            val apiFiles = files(apiVerification)
            doLast {
                val artifact = apiFiles.singleFile
                val actual = Hashes.sha256(artifact)
                if (actual != expectedSha256) {
                    throw GradleException(
                        "$laneLabel $mcVersion API 校验失败：expected=$expectedSha256, actual=$actual",
                    )
                }
                logger.lifecycle("$laneLabel $mcVersion API 校验通过：${artifact.name} $actual")
            }
        }

    tasks.named("compileJava").configure { dependsOn(verifyTask) }
    afterEvaluate {
        val acceptance = extensions.getByType(SourceSetContainer::class.java).findByName(acceptanceSourceSetName)
        acceptance?.let { sourceSet ->
            tasks.named(sourceSet.compileJavaTaskName).configure { dependsOn(verifyTask) }
        }
    }
}
