package buildconventions

import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * 打包校验的公共外壳：车道保留任务注册与依赖接线，只提供"断言清单"。
 *
 * 统一承担：产物解析（product / acceptance）、输入声明、失败文案前缀。
 * 断言内容仍是车道自身的事实（条目路径、元数据字段），集中放在调用方 lambda 里，
 * 可用动词见 [PackagingAssertions]。
 */
fun Task.packagingVerification(
    laneLabel: String,
    mcVersion: String = "",
    product: Provider<RegularFile>,
    acceptance: Provider<RegularFile>? = null,
    checks: PackagingAssertions.(product: JarView, acceptance: JarView?) -> Unit,
) {
    inputs.property("laneLabel", laneLabel)
    if (mcVersion.isNotEmpty()) {
        inputs.property("mcVersion", mcVersion)
    }
    inputs.file(product).withPropertyName("productJar")
    acceptance?.let { inputs.file(it).withPropertyName("acceptanceJar") }

    val assertions = PackagingAssertions(laneLabel, logger)
    doLast {
        val productJar = JarView(product.get().asFile)
        val acceptanceJar = acceptance?.let { JarView(it.get().asFile) }
        assertions.checks(productJar, acceptanceJar)
    }
}
