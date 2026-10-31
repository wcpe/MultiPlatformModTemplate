package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val BYTES_PER_MEGABYTE = 1024L * 1024L

/**
 * Paperclip jar 的缓存解析。
 *
 * <p>冻结车道按 build + 大小 + SHA-256 三重校验，非冻结车道取当前最新 build；
 * 两者的共同点是「缓存里那个 jar 从哪来、可不可信」，与进程托管无关，
 * 因此从 [PaperHostService] 抽出单独成类。
 */
internal class PaperJarCache(
    private val cacheDir: File,
    private val logger: Logger,
) {
    /** 已冻结时走 [FrozenPaperArtifact] 校验，否则取最新构建。 */
    fun resolve(mcVersion: String, frozen: FrozenPaperArtifact?): File =
        frozen?.let(::resolveFrozen) ?: resolveLatest(mcVersion)

    private fun resolveFrozen(frozen: FrozenPaperArtifact): File {
        cacheDir.mkdirs()
        val target = File(cacheDir, frozen.jarName())
        if (target.isFile) {
            frozen.verify(target)
            logger.lifecycle("[mpmt-realserver] 复用冻结 paperclip jar：${target.name}")
            return target
        }
        val temporary = File(cacheDir, "${frozen.jarName()}.${UUID.randomUUID()}.part")
        logger.lifecycle("[mpmt-realserver] 下载冻结 paperclip jar：${frozen.jarName()}")
        try {
            FillV3Downloads.download(frozenDownloadUrl(frozen), temporary)
            frozen.verify(temporary)
            replaceAtomically(temporary, target)
        } finally {
            // 成功时临时文件已被原子改名移走，delete 是空操作；失败时不留半截 jar 给下次复用。
            temporary.delete()
        }
        logger.lifecycle("[mpmt-realserver] 冻结 paperclip jar 就绪：${target.name}")
        return target
    }

    /** 取冻结 build 的可信下载地址：元数据 build 必须等于冻结值，且地址落在受信主机。 */
    private fun frozenDownloadUrl(frozen: FrozenPaperArtifact): String {
        val metadataUrl =
            "${FillV3Downloads.ENDPOINT}projects/paper/versions/${frozen.mcVersion}" +
                "/builds/${frozen.build}"
        val json = metadataJson(metadataUrl)
        if (FillV3Downloads.parseLatestBuildId(json) != frozen.build) {
            throw GradleException(
                "[mpmt-realserver] Paper 元数据 build 与冻结值不一致：$metadataUrl",
            )
        }
        val downloadUrl = FillV3Downloads.parseDownloadUrl(json, FillV3Downloads.KEY_PRIMARY)
        if (!FrozenPaperArtifact.isTrustedDownloadUrl(downloadUrl)) {
            throw GradleException("[mpmt-realserver] Paper 下载地址不受信任：$downloadUrl")
        }
        return downloadUrl
    }

    private fun resolveLatest(mcVersion: String): File {
        cacheDir.mkdirs()
        cacheDir
            .listFiles { file -> file.name.matches(Regex("paper-$mcVersion-\\d+\\.jar")) }
            ?.maxByOrNull { it.name }
            ?.let {
                logger.lifecycle("[mpmt-realserver] 复用缓存 paperclip jar：${it.name}")
                return it
            }
        val latestUrl = "${FillV3Downloads.ENDPOINT}projects/paper/versions/$mcVersion/builds/latest"
        logger.lifecycle("[mpmt-realserver] 查询 PaperMC 最新构建：$latestUrl")
        val json = metadataJson(latestUrl)
        val build = FillV3Downloads.parseLatestBuildId(json)
        val downloadUrl = FillV3Downloads.parseDownloadUrl(json, FillV3Downloads.KEY_PRIMARY)
        val target = File(cacheDir, "paper-$mcVersion-$build.jar")
        logger.lifecycle("[mpmt-realserver] 下载 paperclip jar：$downloadUrl")
        downloadJar(downloadUrl, target)
        logger.lifecycle(
            "[mpmt-realserver] paperclip jar 就绪：${target.name}" +
                "（${target.length() / BYTES_PER_MEGABYTE} MB）",
        )
        return target
    }

    /** 下载到 `.part` 再改名：失败不留半截 jar，异常统一附上 URL 上下文。 */
    private fun downloadJar(url: String, target: File) {
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            FillV3Downloads.download(url, temporary)
            temporary.renameTo(target)
        } catch (failure: IOException) {
            throw GradleException(
                "[mpmt-realserver] 下载 paperclip jar 失败：$url（${failure.message}）",
                failure,
            )
        } finally {
            temporary.delete()
        }
    }

    /** 取元数据 JSON；网络失败统一转成带 URL 的构建失败文案。 */
    private fun metadataJson(url: String): String =
        try {
            FillV3Downloads.fetchText(url)
        } catch (failure: IOException) {
            throw GradleException("[mpmt-realserver] 访问 PaperMC Fill v3 API 失败：$url", failure)
        }

    private fun replaceAtomically(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
