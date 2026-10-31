package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.util.zip.ZipFile

/** 部署总超时：实测 sha256/杀毒扫描在慢盘上可达数十秒。 */
private const val JAR_DEPLOY_TIMEOUT_MS = 60_000L
private const val JAR_DEPLOY_RETRY_MS = 500L
private const val MILLIS_PER_SECOND = 1_000L
private const val BYTES_PER_KILOBYTE = 1024L

/** 单次部署尝试的结果。 */
private sealed interface DeployAttempt {
    /** 源 jar 尚未落定（大小为零或还不是可加载插件）。 */
    data object NotReady : DeployAttempt

    /** 校验通过，[kilobytes] 用于日志。 */
    data class Deployed(
        val kilobytes: Long,
    ) : DeployAttempt

    /** 校验失败，[reason] 用作最终失败文案。 */
    data class Failed(
        val reason: String,
    ) : DeployAttempt
}

/**
 * 把插件 jar 放进 `plugins/`：先清理同名前缀的旧版本，再以「拷贝 → 复核大小与可加载性」重试到稳定。
 *
 * <p>从 [PaperHostService] 抽出：这里只关心文件搬运与校验，与进程托管无关。
 * 重试是必要的——上游 `copyTo` 可能读到仍在写入的 jar（大小稳定但内容不完整）。
 */
internal class PluginJarDeployer(
    private val logger: Logger,
) {
    /** 清理同前缀旧版本后部署 [source]；[label] 只用于缺失时的失败文案。 */
    fun replace(pluginsDir: File, source: File, label: String) {
        require(source.exists()) {
            "[mpmt-realserver] $label 缺失：${source.absolutePath}"
        }
        purgeStale(pluginsDir, source.name)
        deploy(source, File(pluginsDir, source.name))
    }

    /** 删除 `plugins/` 下与当前 jar 同前缀（同名直到最后一个 `-`）的其它 jar。 */
    private fun purgeStale(pluginsDir: File, currentJarName: String) {
        val withoutExt = currentJarName.removeSuffix(".jar")
        val lastDash = withoutExt.lastIndexOf('-')
        if (lastDash <= 0) return
        val basePrefix = withoutExt.substring(0, lastDash + 1)
        pluginsDir
            .listFiles { file ->
                file.isFile &&
                    file.name.endsWith(".jar") &&
                    file.name.startsWith(basePrefix) &&
                    file.name != currentJarName
            }?.forEach { stale ->
                if (runCatching { stale.delete() }.getOrDefault(false)) {
                    logger.lifecycle("[mpmt-realserver] 清理旧版本插件 jar：${stale.name}")
                }
            }
    }

    /** 重试到 [JAR_DEPLOY_TIMEOUT_MS] 为止；每次尝试都重新核对源与目标的一致性。 */
    private fun deploy(source: File, target: File) {
        val deadline = System.currentTimeMillis() + JAR_DEPLOY_TIMEOUT_MS
        var lastError = "未知原因"
        var waitingLogged = false
        while (System.currentTimeMillis() < deadline) {
            when (val attempt = attemptDeploy(source, target)) {
                is DeployAttempt.Deployed -> {
                    logger.lifecycle(
                        "[mpmt-realserver] 已布置插件 jar：${source.name}（${attempt.kilobytes} KB）",
                    )
                    return
                }
                DeployAttempt.NotReady -> {
                    if (!waitingLogged) {
                        logger.lifecycle("[mpmt-realserver] 源插件 jar 尚未就绪，等待……")
                        waitingLogged = true
                    }
                }
                is DeployAttempt.Failed -> lastError = attempt.reason
            }
            Thread.sleep(JAR_DEPLOY_RETRY_MS)
        }
        throw GradleException(
            "[mpmt-realserver] 插件 jar 在 ${JAR_DEPLOY_TIMEOUT_MS / MILLIS_PER_SECOND}s 内未能稳定部署" +
                "：$lastError（源=${source.absolutePath}）",
        )
    }

    private fun attemptDeploy(source: File, target: File): DeployAttempt {
        val sizeBefore = source.length()
        if (sizeBefore <= 0L || !isLoadablePluginJar(source)) {
            return DeployAttempt.NotReady
        }
        val copied = runCatching { source.copyTo(target, overwrite = true) }.isSuccess
        val sizeAfter = source.length()
        return if (isDeployVerified(copied, sizeBefore, sizeAfter, target)) {
            DeployAttempt.Deployed(sizeBefore / BYTES_PER_KILOBYTE)
        } else {
            DeployAttempt.Failed(
                "部署后校验失败（源拷前=$sizeBefore 源拷后=$sizeAfter 目标=${target.length()}）",
            )
        }
    }

    /** 部署成功的判据：拷贝成功、源在拷贝期间未变、目标大小相符、目标是可加载插件。 */
    private fun isDeployVerified(
        copied: Boolean,
        sizeBefore: Long,
        sizeAfter: Long,
        target: File,
    ): Boolean {
        val sizesMatch = sizeAfter == sizeBefore && target.length() == sizeBefore
        return copied && sizesMatch && isLoadablePluginJar(target)
    }

    /** Bukkit / Paper 插件都以 `plugin.yml` 或 `paper-plugin.yml` 声明入口。 */
    private fun isLoadablePluginJar(file: File): Boolean =
        runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("plugin.yml") != null || zip.getEntry("paper-plugin.yml") != null
            }
        }.getOrDefault(false)
}
