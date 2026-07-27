package top.wcpe.mc.mpmt.platform.fabric.version;

import net.minecraft.resources.Identifier;

/**
 * MC 26.2 的 {@link Identifier} 构造入口。main / gametest 共用本工具，避免重复拼接资源标识。
 */
public final class FabricResourceLocations {

    private FabricResourceLocations() {
        // 工具类不实例化
    }

    /** 按命名空间与路径构造 Identifier。 */
    public static Identifier of(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
