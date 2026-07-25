package top.wcpe.mc.mpmt.platform.fabric.version;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.resources.ResourceLocation;

/**
 * 跨 1.20 / 1.21 的 {@link ResourceLocation} 构造：1.20 用公开双参构造，1.21 改为
 * {@code fromNamespaceAndPath}（构造 private）。main / gametest 共用本工具，避免 L3 直接绑死 API。
 */
public final class FabricResourceLocations {

    private FabricResourceLocations() {
        // 工具类不实例化
    }

    /** 按命名空间与路径构造 ResourceLocation。 */
    public static ResourceLocation of(String namespace, String path) {
        try {
            Method factory =
                    ResourceLocation.class.getMethod(
                            "fromNamespaceAndPath", String.class, String.class);
            return (ResourceLocation) factory.invoke(null, namespace, path);
        } catch (NoSuchMethodException ignored) {
            // 1.20.1 路径
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ResourceLocation.fromNamespaceAndPath 调用失败", e);
        }
        try {
            Constructor<ResourceLocation> ctor =
                    ResourceLocation.class.getConstructor(String.class, String.class);
            return ctor.newInstance(namespace, path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法构造 ResourceLocation：" + namespace + ":" + path, e);
        }
    }
}
