package top.wcpe.mc.mpmt.platform.fabric.api;

/**
 * Fabric 平台对外 API 门面：供玩法扩展与跨版本 common 引用的稳定契约。
 *
 * <p>不包含具体 Minecraft/Bukkit/Fabric 类型；实现由版本工程注入。
 * 后续可在此扩展调度、消息、连接等平台侧能力出口。
 */
public interface FabricPlatformApi {

    /** 平台 id（与 SPI platformId 一致）。 */
    String platformId();
}
