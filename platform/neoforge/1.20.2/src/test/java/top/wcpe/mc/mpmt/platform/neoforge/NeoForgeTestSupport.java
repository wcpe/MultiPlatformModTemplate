package top.wcpe.mc.mpmt.platform.neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import sun.misc.Unsafe;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;

/**
 * NeoForge 1.20.2 测试基座：纯 JVM 下造出可用的服务端 / 玩家 / 命令源替身。
 *
 * <p>手法（仓库既有约定）：{@code Bootstrap.bootStrap()} 初始化注册表；{@link Unsafe#allocateInstance}
 * 跳过构造函数造实例；反射写入字段。所有替身共享同一套注入，避免各测试重复实现。
 *
 * <p>注意：{@code FMLEnvironment.dist}、{@code FMLPaths}、{@code Minecraft.instance} 等都是静态状态，
 * 凡改动它们的测试必须自行还原。
 */
public final class NeoForgeTestSupport {

    private NeoForgeTestSupport() {
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

    /**
     * 重置 NeoForge 网络注册表。
     *
     * <p>{@code NetworkRegistry} 以静态表记录已注册通道，同一 JVM 内二次注册同名通道会失败；
     * 测试间须清表以隔离用例（真实运行期只在启动时注册一次）。
     */
    public static void resetNetworkRegistry() {
        try {
            Field instances = Class.forName("net.neoforged.neoforge.network.NetworkRegistry")
                    .getDeclaredField("instances");
            instances.setAccessible(true);
            instances.set(null, new java.util.HashMap<>());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("重置 NeoForge 网络注册表失败", error);
        }
    }

    /**
     * 注入空的 mod 列表，使 {@code ModList.get()} 在纯 JVM 下可用。
     *
     * <p>真实装载器由启动流程填充；测试用空列表模拟「未加载任何 mod」，版本号退化为 unknown。
     */
    public static void installEmptyModList() {
        bootstrapMinecraft();
        try {
            setStatic(Class.forName("net.neoforged.fml.ModList"), "INSTANCE", modListOf());
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 ModList 类", error);
        }
    }

    private static Object modListOf() {
        try {
            Class<?> type = Class.forName("net.neoforged.fml.ModList");
            Method factory = type.getDeclaredMethod("of", java.util.List.class, java.util.List.class);
            factory.setAccessible(true);
            Object list = factory.invoke(null, new java.util.ArrayList<>(), new java.util.ArrayList<>());
            // of() 只填容器列表，索引表须显式补齐，否则按 id 查询会空指针
            set(list, "indexedMods", new java.util.HashMap<>());
            set(list, "mods", new java.util.ArrayList<>());
            return list;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("构造空 mod 列表失败", error);
        }
    }

    /**
     * 注入 FML 版本元数据，使运行期版本探测在纯 JVM 下可用。
     *
     * <p>{@code FMLLoader.versionInfo()} 在真实装载器里由启动流程填入；测试须自行注入。
     */
    public static void installFmlVersionInfo(String mcVersion) {        bootstrapMinecraft();
        try {
            Class<?> versionInfoType = Class.forName("net.neoforged.fml.loading.VersionInfo");
            Object info = versionInfoType
                    .getConstructor(String.class, String.class, String.class, String.class)
                    .newInstance("20.2.93", "4.0.0", mcVersion, "20230816");
            setStatic(Class.forName("net.neoforged.fml.loading.FMLLoader"), "versionInfo", info);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("注入 FML 版本元数据失败", error);
        }
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
    public static Minecraft newClient() {
        bootstrapMinecraft();
        Minecraft minecraft = allocate(Minecraft.class);
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
    public static net.minecraft.server.network.ServerGamePacketListenerImpl newListener() {
        return newListener(null);
    }

    /** 造一个报文监听替身；传入服务端以解锁需要 server 的断开路径。 */
    public static net.minecraft.server.network.ServerGamePacketListenerImpl newListener(
            MinecraftServer server) {
        bootstrapMinecraft();
        net.minecraft.server.network.ServerGamePacketListenerImpl listener =
                allocate(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        set(listener, "connection", newConnection());
        set(listener, "server", server);
        return listener;
    }

    /** 造一个连接替身：已初始化待发队列，send 不会抛出。 */
    public static net.minecraft.network.Connection newConnection() {
        bootstrapMinecraft();
        net.minecraft.network.Connection connection = allocate(net.minecraft.network.Connection.class);
        set(connection, "pendingActions", new java.util.ArrayDeque<>());
        set(connection, "receiving", net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        return connection;
    }

    /** 造一个世界替身，仅注入维度键（供世界端口查询）。 */
    public static ServerLevel newLevel(ResourceKey<Level> dimension) {
        bootstrapMinecraft();
        ServerLevel level = allocate(ServerLevel.class);
        setField(level, field(Level.class, "dimension"), dimension);
        return level;
    }

    /** 把世界替身装入服务端替身的维度表。 */
    public static void putLevel(MinecraftServer server, ServerLevel level) {
        levels(server).put(level.dimension(), level);
    }

    /**
     * 把玩家表装入服务端替身（含 UUID 索引与在线视图）。
     *
     * <p>NeoForge 的 {@code PlayerList} 走 {@code playersView} 只读视图，故两者都要注入。
     */
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
        server.setPlayerList(playerList);
    }
    /**

     * 造一个命令源替身：记录成功与失败回执，权限与静默由调用方指定。
     *
     * <p>先把服务端替身注入，使 {@code source.getServer().execute(...)} 能就地执行异步回执。
     */
    public static CommandSourceStack newCommandSource(int permissionLevel) {
        CommandSourceStack source = allocate(CommandSourceStack.class);
        set(source, "permissionLevel", permissionLevel);
        set(source, "textName", "测试命令源");
        set(source, "displayName", Component.literal("测试命令源"));
        set(source, "silent", false);
        set(source, "server", newServer());
        set(source, "source", recordingCommandSource());
        return source;
    }

    /** 取命令源记录到的全部回执文本（成功与失败按序合并）。 */
    public static java.util.List<String> messagesOf(CommandSourceStack source) {
        CommandSource recorder = (CommandSource) getField(source, field(CommandSourceStack.class, "source"));
        return ((RecordingCommandSource) recorder).messages;
    }

    /** 最近一条回执文本；无回执时为空。 */
    public static String lastMessageOf(CommandSourceStack source) {
        java.util.List<String> messages = messagesOf(source);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
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

    /** 用 {@link Unsafe} 跳过构造函数分配实例（随后用 {@link #set} 注入所需字段）。 */
    public static <T> T allocate(Class<T> type) {
        bootstrapMinecraft();
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafe.get(null);
            return type.cast(unsafe.allocateInstance(type));
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

    public static Object getStatic(Class<?> type, String fieldName) {
        try {
            Field target = type.getDeclaredField(fieldName);
            target.setAccessible(true);
            return target.get(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("读取静态字段失败 " + type.getName() + "#" + fieldName, error);
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

    /** 调用私有 / 包私有静态方法。 */
    public static Object invokeStatic(Class<?> type, String methodName, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = type.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("调用静态方法失败 " + methodName, error);
        }
    }

    private static CommandSource recordingCommandSource() {
        return new RecordingCommandSource();
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

    /** 记录回执的命令源替身：成功与失败都按序记录文本。 */
    private static final class RecordingCommandSource implements CommandSource {

        private final java.util.List<String> messages = new java.util.ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
