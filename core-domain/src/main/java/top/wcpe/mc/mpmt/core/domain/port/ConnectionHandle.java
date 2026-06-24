package top.wcpe.mc.mpmt.core.domain.port;

/**
 * 不透明连接句柄：标识一个跨端连接。
 *
 * <p>平台真实连接对象（Bukkit 的 Player、Netty 的 Connection 等）封装在 L3 内，L0/L1 只持有本句柄、
 * 不解析其类型（network spec §3.7 / ADR-0001：平台对象不进 L0/L1）。
 */
public interface ConnectionHandle {
}
