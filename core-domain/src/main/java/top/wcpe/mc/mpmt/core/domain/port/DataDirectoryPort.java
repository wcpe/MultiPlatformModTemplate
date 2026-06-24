package top.wcpe.mc.mpmt.core.domain.port;

import java.nio.file.Path;

/**
 * 数据基目录端口（L0）：由平台提供本插件 / mod 的基目录（Bukkit 插件数据目录 / Fabric 配置目录等）。
 *
 * <p>共享层（core-paths 等）只在此基目录下拼相对预设位置，<b>不硬编码绝对路径</b>
 * （ADR-0010 / architecture-invariants）。客户端与服务端共用同一端口契约。
 *
 * <p>{@link Path} 为 JDK 标准类型、非平台原生类型，故可出现在 L0（守 ADR-0001）。
 */
@FunctionalInterface
public interface DataDirectoryPort {

    /** 平台提供的基目录（绝对路径），所有预设位置相对它解析。 */
    Path baseDirectory();
}
