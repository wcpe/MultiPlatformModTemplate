package top.wcpe.mc.mpmt.platform.forge.modern;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraftforge.network.Channel;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayload;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;

/**
 * Forge 1.21.1 平台测试的 Minecraft 基座：纯 JVM 下造出可用的服务端 / 玩家 / 世界替身。
 *
 * <p>手法沿用仓库既有约定：{@code Bootstrap.bootStrap()} 初始化 MC 注册表；{@code Unsafe.allocateInstance}
 * 跳过构造函数造实例；反射写入字段。
 *
 * <p><b>引导边界</b>：Forge 52 的 {@code Bootstrap.bootStrap()} 会经 FML 网络栈初始化而失败——
 * 该栈依赖 ModLauncher 的类变换（纯 JVM 无之）——此时 MC 注册表引导<b>已经完成</b>，故吞掉该失败继续。
 *
 * <p><b>通道替身</b>：{@code ChannelBuilder} 构建 {@code PayloadChannel} 时会注册事件监听器，
 * 同样依赖 ModLauncher 的 ASM 处理器，故 {@link ForgeTypedPayloadChannel} 经 {@code Unsafe} 造实例、
 * 只注入类型与通道替身，绕开构造期注册。
 */
public final class Forge121MinecraftTestSupport {

    private Forge121MinecraftTestSupport() {
        // 工具类不实例化
    }

    private static volatile boolean bootstrapped;

    /** 初始化 MC 注册表（幂等；Forge 网络栈初始化失败不阻断，见类注释）。 */
    public static void bootstrapMinecraft() {
        if (bootstrapped) {
            return;
        }
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络握手栈在纯 JVM 下不可初始化；注册表引导先于此完成，可安全继续
        }
        bootstrapped = true;
    }

    /** 造一个服务端替身：真实事件循环、空维度表、空玩家表。 */
    public static MinecraftServer newServer() {
        bootstrapMinecraft();
        MinecraftServer server = allocateInstance(DedicatedServer.class);
        set(server, "serverThread", Thread.currentThread());
        set(server, "profiler", InactiveProfiler.INSTANCE);
        set(server, "levels", new HashMap<Object, Object>());
        set(server, "pendingRunnables", new ConcurrentLinkedQueue<Runnable>());
        DedicatedPlayerList playerList = allocateInstance(DedicatedPlayerList.class);
        set(playerList, "server", server);
        set(playerList, "players", new ArrayList<ServerPlayer>());
        set(playerList, "playersView", new ArrayList<ServerPlayer>());
        set(playerList, "playersByUUID", new HashMap<UUID, ServerPlayer>());
        server.setPlayerList(playerList);
        return server;
    }

    /**
     * 造一个服务端替身并把其"服务端线程"标记为另一线程。
     *
     * <p>{@code MinecraftServer#execute} 在调用线程即服务端线程时直接同步执行；标记为外来线程后
     * 任务会真正进入事件循环队列，从而可断言"提交到服务端线程"的转发语义。
     */
    public static MinecraftServer newForeignThreadServer() {
        MinecraftServer server = newServer();
        set(server, "serverThread", new Thread(() -> { }, "mpmt-测试服务端线程"));
        return server;
    }

    /** 造一个玩家替身：注入 UUID 与游戏档案，使 {@code getUUID()} / {@code getName()} 可用。 */
    public static ServerPlayer newPlayer(UUID playerId, String name) {
        bootstrapMinecraft();
        ServerPlayer player = allocateInstance(ServerPlayer.class);
        setField(player, field(net.minecraft.world.entity.Entity.class, "uuid"), playerId);
        set(player, "gameProfile", new com.mojang.authlib.GameProfile(playerId, name));
        return player;
    }

    /** 造一个世界替身，仅注入维度键（供世界端口查询）。 */
    public static ServerLevel newLevel(String dimensionId) {
        bootstrapMinecraft();
        ServerLevel level = allocateInstance(ServerLevel.class);
        setField(level, field(net.minecraft.world.level.Level.class, "dimension"), dimensionKey(dimensionId));
        return level;
    }

    /** 把世界替身装入服务端替身的维度表。 */
    public static void putLevel(MinecraftServer server, ServerLevel level) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> levels = (Map<Object, Object>) getField(server, field(MinecraftServer.class, "levels"));
        levels.put(dimensionKey(level.dimension().location().toString()), level);
    }

    /** 把玩家表装入服务端替身（含 UUID 索引与在线列表）。 */
    public static void installPlayerList(MinecraftServer server, ServerPlayer... players) {
        DedicatedPlayerList playerList = allocateInstance(DedicatedPlayerList.class);
        set(playerList, "server", server);
        List<ServerPlayer> online = new ArrayList<>();
        Map<UUID, ServerPlayer> byId = new HashMap<>();
        for (ServerPlayer player : players) {
            online.add(player);
            byId.put(player.getUUID(), player);
        }
        set(playerList, "players", online);
        set(playerList, "playersView", online);
        set(playerList, "playersByUUID", byId);
        server.setPlayerList(playerList);
    }

    /** 取出并在当前线程执行服务端替身事件循环里已排队的任务。 */
    public static int drainPendingTasks(MinecraftServer server) {
        @SuppressWarnings("unchecked")
        java.util.Queue<Runnable> pending =
                (java.util.Queue<Runnable>) getField(server, field(MinecraftServer.class, "pendingRunnables"));
        int executed = 0;
        Runnable task = pending.poll();
        while (task != null) {
            task.run();
            executed++;
            task = pending.poll();
        }
        return executed;
    }

    /** 造一个产品类型化通道替身：仅注入载荷类型与通道替身，不触发 Forge 事件总线注册。 */
    public static ForgeTypedPayloadChannel newTypedChannel() {
        return newTypedChannel(ResourceLocation.fromNamespaceAndPath("mpmt", "test-" + UUID.randomUUID()));
    }

    /**
     * 造一个绑定到指定资源位置的产品类型化通道替身。
     *
     * <p>{@code sendToPlayer} / {@code sendToServer} 会真实编码包体，故通道替身需可调用。
     */
    public static ForgeTypedPayloadChannel newTypedChannel(ResourceLocation channelId) {
        bootstrapMinecraft();
        ForgeTypedPayloadChannel channel = allocateInstance(ForgeTypedPayloadChannel.class);
        set(channel, "type", new CustomPacketPayload.Type<ForgeTypedPayload>(channelId));
        set(channel, "channel", newChannelStub());
        return channel;
    }

    /**
     * 造一个底层 Forge 通道替身（{@code PayloadChannel} 为包私有 final 类，经 {@code Unsafe} 分配）。
     *
     * <p>只注入载荷表，使 {@code Channel#getName} 等只读路径可用；真实发包路径在纯 JVM 下不可达，
     * 由测试按"参数校验分支"覆盖。
     */
    private static Channel<CustomPacketPayload> newChannelStub() {
        try {
            @SuppressWarnings("unchecked")
            Channel<CustomPacketPayload> stub = (Channel<CustomPacketPayload>)
                    allocateInstance(Class.forName("net.minecraftforge.network.PayloadChannel"));
            set(stub, "payloads", new HashMap<Object, Object>());
            return stub;
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge 载荷通道类型", error);
        }
    }

    /**
     * 清空 Forge 通道注册表：该表为进程级静态状态，而产品通道固定为 {@code mpmt:main}，
     * 多次构造产品通道会撞键（{@code Channel mpmt:main already registered}）。
     */
    public static void resetChannelRegistry() {
        try {
            Class<?> registry = Class.forName("net.minecraftforge.network.NetworkRegistry");
            setStatic(registry, "instances", new HashMap<Object, Object>());
            setStatic(registry, "lock", Boolean.FALSE);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge 网络注册表", error);
        }
    }

    /**
     * 预置 Forge 事件总线的监听器表缓存，使 {@code @SubscribeEvent} 注册在纯 JVM 下可用。
     *
     * <p>Forge 的事件总线经"无参构造事件实例再问其监听器表"推导监听器表；而事件类（如
     * {@code CustomPayloadEvent}）没有无参构造器，且该推导还需要 ModLauncher 的 ASM 处理器。
     * 纯 JVM 下改为预先按事件类层次填入 {@code ListenerList}（父列表为基），绕开两条不可用路径。
     *
     * @param eventTypes 需要预置的事件类型（含父类会被递归预置）
     */
    public static void primeEventListeners(Class<?>... eventTypes) {
        for (Class<?> eventType : eventTypes) {
            primeEventListener(eventType);
        }
    }

    @SuppressWarnings("unchecked")
    private static void primeEventListener(Class<?> eventType) {
        try {
            Class<?> helper = Class.forName("net.minecraftforge.eventbus.api.EventListenerHelper");
            Field listeners = helper.getDeclaredField("listeners");
            listeners.setAccessible(true);
            Object cache = listeners.get(null);
            Field mapField = cache.getClass().getDeclaredField("map");
            mapField.setAccessible(true);
            Map<Object, Object> map = (Map<Object, Object>) mapField.get(cache);

            Class<?> eventClass = Class.forName("net.minecraftforge.eventbus.api.Event");
            Class<?> listenerList = Class.forName("net.minecraftforge.eventbus.ListenerList");
            java.lang.reflect.Constructor<?> constructor = listenerList.getDeclaredConstructor(listenerList);
            constructor.setAccessible(true);

            if (!map.containsKey(eventClass)) {
                map.put(eventClass, constructor.newInstance((Object) null));
            }
            Class<?> parent = eventType.getSuperclass();
            if (parent == null || parent == Object.class) {
                parent = eventClass;
            }
            if (!map.containsKey(parent)) {
                primeEventListener(parent);
            }
            if (!map.containsKey(eventType)) {
                map.put(eventType, constructor.newInstance(map.get(parent)));
            }
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge 事件总线类型", error);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法预置事件监听器表 " + eventType.getName(), error);
        }
    }

    /**
     * 把 {@code FMLPaths.CONFIGDIR} 指向给定目录（该枚举常量持有可写非 final 的绝对路径字段）。
     *
     * <p>枚举自身的类初始化在纯 JVM 下会给出 null 路径，故必须在注入后才读取 {@code get()}。
     */
    public static void replaceConfigDirectory(Path directory) {
        bootstrapMinecraft();
        try {
            Class<?> paths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
            Object configDir = paths.getField("CONFIGDIR").get(null);
            set(configDir, "absolutePath", directory);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge 路径枚举", error);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法指向测试配置目录", error);
        }
    }

    /** 造一个客户端网络连接替身：内嵌 Netty 通道 + 待发队列，可真实编码包体。 */
    public static net.minecraft.network.Connection newClientConnection() {
        bootstrapMinecraft();
        net.minecraft.network.Connection connection = allocateInstance(net.minecraft.network.Connection.class);
        set(connection, "channel", new io.netty.channel.embedded.EmbeddedChannel());
        set(connection, "pendingActions", new ConcurrentLinkedQueue<Object>());
        set(connection, "address", new java.net.InetSocketAddress("127.0.0.1", 25566));
        return connection;
    }

    /** 装一个空 mod 表：使 {@code ModList.get()} 可用、{@code getModContainerById} 一律返回空。 */
    public static void installEmptyModList() {
        bootstrapMinecraft();
        try {
            Class<?> modList = Class.forName("net.minecraftforge.fml.ModList");
            Object instance = allocateInstance(modList);
            set(instance, "mods", new ArrayList<Object>());
            set(instance, "indexedMods", new HashMap<String, Object>());
            setStatic(modList, "INSTANCE", instance);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge mod 清单类型", error);
        }
    }

    /** 用 {@link sun.misc.Unsafe} 跳过构造函数分配实例（供测试随后反射注入所需字段）。 */
    public static <T> T allocateInstance(Class<T> type) {
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法分配 " + type.getName(), error);
        }
    }

    public static void set(Object target, String fieldName, Object value) {
        setField(target, field(target.getClass(), fieldName), value);
    }

    /** 逐级向上查找字段（含父类私有字段）。 */
    public static Field field(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field found = current.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("找不到字段 " + type.getName() + "#" + name);
    }

    public static void setField(Object target, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("写入字段失败 " + field, error);
        }
    }

    /** 写入静态字段。 */
    public static void setStatic(Class<?> owner, String name, Object value) {
        try {
            Field target = field(owner, name);
            target.set(null, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("写入静态字段失败 " + owner.getName() + "#" + name, error);
        }
    }

    public static Object getField(Object target, Field field) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("读取字段失败 " + field, error);
        }
    }

    /** 造一个数据目录替身。 */
    public static Path fixtureDirectory(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), "mpmt-forge121-" + name + "-" + UUID.randomUUID());
    }

    private static ResourceKey<net.minecraft.world.level.Level> dimensionKey(String dimensionId) {
        return ResourceKey.create(Registries.DIMENSION, resourceLocation(dimensionId));
    }

    private static ResourceLocation resourceLocation(String dimensionId) {
        int split = dimensionId.indexOf(':');
        if (split < 0) {
            return ResourceLocation.withDefaultNamespace(dimensionId);
        }
        return ResourceLocation.fromNamespaceAndPath(
                dimensionId.substring(0, split), dimensionId.substring(split + 1));
    }
}
