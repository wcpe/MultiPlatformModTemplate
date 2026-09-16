package top.wcpe.mc.mpmt.platform.fabric;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Fabric 事件替身：在纯 JVM 下驱动 {@code Event} 监听器，验证产品钩子的真实转发。
 *
 * <p>产品入口在 {@code onInitialize()} 里把回调注册到 Fabric API 事件；测试经本类取回实时监听器列表，
 * 再按真实参数签名调用，等价于 Fabric 运行期的事件触发，无需启动游戏。
 *
 * <p>同时提供 fabric-loader 元数据替身，使 {@code FabricLoader.getModContainer("minecraft")} 可返回
 * 指定版本串，从而覆盖依赖运行期元数据的生产代码路径。
 */
public final class FabricTestSupport {

    private FabricTestSupport() {
        // 工具类不实例化
    }

    /** 取事件已注册的监听器（按注册顺序）。 */
    @SuppressWarnings("unchecked")
    public static <T> List<T> listeners(Event<T> event) {
        try {
            Field field = findField(event.getClass(), "handlers");
            Object[] array = (Object[]) field.get(event);
            if (array == null) {
                return new ArrayList<>();
            }
            List<T> result = new ArrayList<>(array.length);
            for (Object listener : array) {
                result.add((T) listener);
            }
            return result;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取事件监听器", e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * 按监听器接口的运行时类型过滤事件监听器。
     *
     * <p>事件在首次注册前为无类型空实现，过滤可避免在未注册时按接口强转失败。
     */
    public static <T> List<T> listenersOf(Event<?> event, Class<T> listenerType) {
        List<T> result = new ArrayList<>();
        for (Object listener : listeners(event)) {
            if (listenerType.isInstance(listener)) {
                result.add(listenerType.cast(listener));
            }
        }
        return result;
    }

    /**
     * 取第 {@code fromIndex} 个之后新注册的监听器。
     *
     * <p>Fabric 事件是进程级静态单例，同一 JVM 内先前的用例留下的监听器仍在列表中；
     * 触发时必须只调用本次用例新增的那些，否则会误触先前用例的产品实例。
     */
    public static <T> List<T> listenersFrom(Event<?> event, Class<T> listenerType, int fromIndex) {
        List<T> all = listenersOf(event, listenerType);
        return fromIndex >= all.size() ? new ArrayList<>() : new ArrayList<>(all.subList(fromIndex, all.size()));
    }

    /** 触发从 {@code fromIndex} 起新增的监听器，返回回调数量。 */
    public static <T> int fireFrom(
            Event<?> event,
            Class<T> listenerType,
            int fromIndex,
            java.util.function.Consumer<T> invocation) {
        List<T> listeners = listenersFrom(event, listenerType, fromIndex);
        for (T listener : listeners) {
            invocation.accept(listener);
        }
        return listeners.size();
    }

    /** 调用实例上的私有 / 包私有方法（按参数类型精确定位重载）。 */
    public static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            java.lang.reflect.Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "反射调用失败 " + target.getClass().getName() + "#" + methodName, e);
        }
    }

    /** 读取实例字段（含父类私有字段）。 */
    public static Object read(Object target, String fieldName) {
        return MinecraftTestSupport.getField(
                target, MinecraftTestSupport.field(target.getClass(), fieldName));
    }

    /**
     * 重置 {@code PlatformProvider} 静态 Holder（含进程级标记）。
     *
     * <p>该重置方法在 SPI 包内为包私有，平台车道测试须经反射调用；静态 Holder 跨用例互相影响，
     * 每个装配用例前后都必须重置。
     */
    public static void resetPlatformProvider() {
        try {
            Class<?> provider = Class.forName("top.wcpe.mc.mpmt.platform.spi.PlatformProvider");
            java.lang.reflect.Method reset = provider.getDeclaredMethod("resetForTesting");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法重置 PlatformProvider", e);
        }
    }

    /** 调用静态私有 / 包私有方法。 */
    public static Object invokeStatic(Class<?> type, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用静态方法失败 " + type.getName() + "#" + methodName, e);
        }
    }

    /** 造一个非空连接监听器（玩家字段已注入），用于驱动 JOIN / DISCONNECT 回调。 */
    public static ServerGamePacketListenerImpl newConnectionHandler(MinecraftServer server, ServerPlayer player) {
        ServerGamePacketListenerImpl handler =
                MinecraftTestSupport.allocateInstance(ServerGamePacketListenerImpl.class);
        MinecraftTestSupport.set(handler, "server", server);
        MinecraftTestSupport.set(handler, "player", player);
        return handler;
    }

    /**
     * 把 fabric-loader 的 {@code minecraft} 元数据指向给定版本串（幂等覆盖）。
     *
     * <p>返回可恢复句柄：测试结束调用 {@link LoaderStub#close()} 复原，避免污染共享单例。
     */
    public static LoaderStub stubMinecraftVersion(String version) {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        Map<String, ModContainerImpl> modMap = modMap(loader);
        Optional<ModContainerImpl> previous = Optional.ofNullable(modMap.get("minecraft"));
        modMap.put("minecraft", container(version));
        return new LoaderStub() {

            @Override
            public String stubbedVersion() {
                return version;
            }

            @Override
            public void close() {
                previous.ifPresentOrElse(
                        restored -> modMap.put("minecraft", restored),
                        () -> modMap.remove("minecraft"));
            }
        };
    }

    /** 加载器元数据替身的句柄：暴露替身版本以确认注入生效，并可复原共享单例。 */
    public interface LoaderStub extends AutoCloseable {

        /** 当前替身对外宣称的版本串。 */
        String stubbedVersion();

        @Override
        void close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModContainerImpl> modMap(FabricLoaderImpl loader) {
        try {
            Field field = FabricLoaderImpl.class.getDeclaredField("modMap");
            field.setAccessible(true);
            return (Map<String, ModContainerImpl>) field.get(loader);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法访问 fabric-loader modMap", e);
        }
    }

    private static ModContainerImpl container(String version) {
        ModContainerImpl container = MinecraftTestSupport.allocateInstance(ModContainerImpl.class);
        MinecraftTestSupport.setField(
                container, MinecraftTestSupport.field(ModContainerImpl.class, "info"), metadata(version));
        return container;
    }

    private static LoaderModMetadata metadata(String version) {
        return (LoaderModMetadata)
                Proxy.newProxyInstance(
                        FabricTestSupport.class.getClassLoader(),
                        new Class<?>[] {LoaderModMetadata.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getVersion":
                                    return modVersion(version);
                                case "getId":
                                    return "minecraft";
                                case "getType":
                                    return "fabric";
                                case "getName":
                                    return "Minecraft";
                                case "toString":
                                    return "minecraft@stub";
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return identityEquals(proxy, args[0]);
                                default:
                                    return null;
                            }
                        });
    }

    private static Version modVersion(String version) {
        return (Version)
                Proxy.newProxyInstance(
                        FabricTestSupport.class.getClassLoader(),
                        new Class<?>[] {Version.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getFriendlyString":
                                case "toString":
                                    return version;
                                case "hashCode":
                                    return version.hashCode();
                                case "equals":
                                    return identityEquals(proxy, args[0]);
                                default:
                                    return null;
                            }
                        });
    }

    /**
     * 动态代理的相等性按实例身份判定。
     *
     * <p>替身只用于让产品代码读到期望的元数据，不参与集合键语义，故身份比较即为预期行为。
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean identityEquals(Object proxy, Object other) {
        return proxy == other;
    }
}
