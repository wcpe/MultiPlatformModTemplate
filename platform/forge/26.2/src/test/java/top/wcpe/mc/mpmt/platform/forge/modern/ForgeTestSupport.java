package top.wcpe.mc.mpmt.platform.forge.modern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;

/**
 * Forge 26.2 测试基座：纯 JVM 下造出可用的服务端 / 客户端 / 玩家 / 连接替身。
 *
 * <p>手法（仓库既有约定）：{@code Bootstrap.bootStrap()} 初始化注册表；{@code sun.misc.Unsafe#allocateInstance}
 * 跳过构造函数造实例；反射写入字段。所有替身共享同一套注入，避免各测试重复实现。
 *
 * <p>注意：{@code Minecraft.instance} 是静态单例，凡改动它的测试必须自行还原。
 */
public final class ForgeTestSupport {

    private ForgeTestSupport() {
        // 工具类不实例化
    }

    private static volatile boolean bootstrapped;

    /** 初始化 MC 注册表（幂等）。 */
    public static void bootstrapMinecraft() {
        if (bootstrapped) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }

    /** 造一个服务端替身：可用的任务队列、空维度表与空玩家表。 */
    public static MinecraftServer newServer() {
        bootstrapMinecraft();
        MinecraftServer server = allocate(DedicatedServer.class);
        set(server, "serverThread", Thread.currentThread());
        set(server, "levels", new java.util.concurrent.ConcurrentHashMap<>());
        // 事件循环队列由构造函数初始化；跳过构造函数后须补上，execute(Runnable) 才能执行任务
        setField(server, field(net.minecraft.util.thread.BlockableEventLoop.class, "pendingRunnables"),
                new java.util.ArrayDeque<>());
        installPlayerList(server);
        return server;
    }

    /** 执行并清空事件循环中已排队的任务，返回执行数量。 */
    public static int drainTasks(MinecraftServer server) {
        java.util.Queue<Runnable> pending = pendingRunnables(server);
        int executed = 0;
        Runnable task;
        while ((task = pending.poll()) != null) {
            task.run();
            executed++;
        }
        return executed;
    }

    /** 造一个客户端单例替身：游戏线程为当前线程，{@code execute} 就地执行任务。 */
    public static net.minecraft.client.Minecraft newClient() {
        bootstrapMinecraft();
        net.minecraft.client.Minecraft minecraft = allocate(net.minecraft.client.Minecraft.class);
        set(minecraft, "gameThread", Thread.currentThread());
        setField(minecraft, field(net.minecraft.util.thread.BlockableEventLoop.class, "pendingRunnables"),
                new java.util.ArrayDeque<>());
        return minecraft;
    }

    /** 造一个玩家替身：注入 UUID 与游戏档案，并补上可用的连接替身。 */
    public static ServerPlayer newPlayer(UUID playerId, String name) {
        bootstrapMinecraft();
        ServerPlayer player = allocate(ServerPlayer.class);
        seedPlayer(player, playerId, name);
        set(player, "connection", newListener());
        return player;
    }

    /** 向已分配的玩家替身写入身份字段，供需要覆写行为的测试复用。 */
    public static void seedPlayer(ServerPlayer player, UUID playerId, String name) {
        setField(player, field(net.minecraft.world.entity.Entity.class, "uuid"), playerId);
        set(player, "gameProfile", new com.mojang.authlib.GameProfile(playerId, name));
    }

    /** 造一个含可用连接的报文监听替身（解锁 sendSystemMessage）。 */
    public static ServerGamePacketListenerImpl newListener() {
        return newListener(null);
    }

    /** 造一个报文监听替身；传入服务端以解锁需要 server 的断开路径。 */
    public static ServerGamePacketListenerImpl newListener(MinecraftServer server) {
        bootstrapMinecraft();
        ServerGamePacketListenerImpl listener = allocate(ServerGamePacketListenerImpl.class);
        set(listener, "connection", newConnection());
        set(listener, "server", server);
        return listener;
    }

    /** 造一个服务端接收方向（SERVERBOUND）的连接替身：已初始化待发队列与协议元数据。 */
    public static Connection newConnection() {
        return newConnection(PacketFlow.SERVERBOUND);
    }

    /** 造指定接收方向的连接替身；方向决定事件上下文的服务端 / 客户端分支。 */
    public static Connection newConnection(PacketFlow receiving) {
        bootstrapMinecraft();
        Connection connection = allocate(Connection.class);
        set(connection, "pendingActions", new java.util.ArrayDeque<>());
        set(connection, "receiving", receiving);
        set(connection, "inboundProtocol", protocolProxy());
        set(connection, "outboundProtocol", protocolProxy());
        return connection;
    }

    /** 把报文监听替身装到连接上，使事件上下文可反解出发送者。 */
    public static void bindSender(Connection connection, ServerPlayer sender) {
        ServerGamePacketListenerImpl listener = newListener();
        set(listener, "connection", connection);
        set(listener, "player", sender);
        set(connection, "packetListener", listener);
    }

    /** 造一个世界替身，仅注入维度键（供世界端口查询）。 */
    public static ServerLevel newLevel(ResourceKey<Level> dimension) {
        bootstrapMinecraft();
        ServerLevel level = allocate(ServerLevel.class);
        setField(level, field(Level.class, "dimension"), dimension);
        return level;
    }

    /** 把世界替身装入服务端替身的维度表（26.2 的 levels 以资源键为键）。 */
    public static void putLevel(MinecraftServer server, ServerLevel level) {
        levels(server).put(level.dimension(), level);
    }

    /** 把玩家表装入服务端替身（含 UUID 索引与在线列表）。 */
    public static void installPlayerList(MinecraftServer server, ServerPlayer... players) {
        PlayerList playerList = allocate(DedicatedPlayerList.class);
        java.util.List<ServerPlayer> online = new java.util.ArrayList<>();
        java.util.Map<UUID, ServerPlayer> byId = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            online.add(player);
            byId.put(player.getUUID(), player);
        }
        set(playerList, "players", online);
        set(playerList, "playersView", java.util.Collections.unmodifiableList(online));
        set(playerList, "playersByUUID", byId);
        set(playerList, "playersByUUIDView", java.util.Collections.unmodifiableMap(byId));
        server.setPlayerList(playerList);
    }

    /** 用最短端口替身填满运行时，满足服务端闭环装配的依赖。 */
    public static MpmtRuntime runtimeWithPorts() {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(PersistencePort.class, emptyPersistence());
        runtime.ports().register(SchedulerPort.class, immediateScheduler());
        runtime.ports().register(ConnectionControlPort.class, noopConnections());
        return runtime;
    }

    /** 空持久化端口替身。 */
    public static PersistencePort emptyPersistence() {
        return new PersistencePort() {
            @Override
            public Optional<String> read(String namespace, String key) {
                return Optional.empty();
            }

            @Override
            public void write(String namespace, String key, String value) {
                // 测试不触发写入
            }
        };
    }

    /** 立即执行调度端口替身。 */
    public static SchedulerPort immediateScheduler() {
        return new SchedulerPort() {
            @Override
            public void runForEntity(EntityRef entity, Runnable task) {
                task.run();
            }

            @Override
            public void runForLocation(WorldRef world, int x, int z, Runnable task) {
                task.run();
            }

            @Override
            public void runGlobal(Runnable task) {
                task.run();
            }

            @Override
            public void runAsync(Runnable task) {
                task.run();
            }

            @Override
            public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
                return () -> { };
            }
        };
    }

    /** 不触发任何实际断开的连接控制端口替身。 */
    public static ConnectionControlPort noopConnections() {
        return new ConnectionControlPort() {
            @Override
            public EntityRef entityOf(ConnectionHandle connection) {
                throw new UnsupportedOperationException("测试不触发连接查询");
            }

            @Override
            public void disconnect(ConnectionHandle connection, String reason) {
                throw new UnsupportedOperationException("测试不触发断开");
            }
        };
    }

    /**
     * 用 {@code sun.misc.Unsafe} 跳过构造函数分配实例（随后用 {@link #set} 注入所需字段）。
     *
     * <p>该类型属 JDK 内部专用 API，源码里直接引用会让 {@code --release 25} 编译告警，故按仓库既有约定
     * （同 1.20.1 / 1.21.1 车道的测试基座）经反射取用，调用语义与直接引用完全一致。
     */
    public static <T> T allocate(Class<T> type) {
        bootstrapMinecraft();
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

    public static void setStatic(Class<?> type, String fieldName, Object value) {
        try {
            Field target = type.getDeclaredField(fieldName);
            target.setAccessible(true);
            target.set(null, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("写入静态字段失败 " + type.getName() + "#" + fieldName, error);
        }
    }

    /** 逐级向上查找字段（含父类私有字段）。 */
    public static Field field(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException error) {
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

    public static Object getField(Object target, Field field) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("读取字段失败 " + field, error);
        }
    }

    /** 调用私有 / 包私有实例方法。 */
    public static Object invoke(Object target, String methodName, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("调用方法失败 " + methodName, error);
        }
    }

    private static Object protocolProxy() {
        return Proxy.newProxyInstance(
                ProtocolInfo.class.getClassLoader(),
                new Class<?>[] {ProtocolInfo.class},
                (proxy, method, args) -> method.getName().equals("id") ? ConnectionProtocol.PLAY : null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Object, Object> levels(MinecraftServer server) {
        return (java.util.Map<Object, Object>) getField(server, field(MinecraftServer.class, "levels"));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Queue<Runnable> pendingRunnables(MinecraftServer server) {
        return (java.util.Queue<Runnable>) getField(server,
                field(net.minecraft.util.thread.BlockableEventLoop.class, "pendingRunnables"));
    }
}
