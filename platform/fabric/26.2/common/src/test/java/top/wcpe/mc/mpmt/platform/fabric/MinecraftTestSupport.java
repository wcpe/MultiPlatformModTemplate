package top.wcpe.mc.mpmt.platform.fabric;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import sun.misc.Unsafe;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricResourceLocations;

/**
 * Fabric 平台测试的 Minecraft 基座：纯 JVM 下造出可用的服务端 / 玩家 / 世界替身。
 *
 * <p>手法（仓库既有约定）：{@code Bootstrap.bootStrap()} 初始化 MC 注册表；{@link Unsafe#allocateInstance}
 * 跳过构造函数造实例；反射写入字段。所有替身共享同一套字段注入，避免各测试重复实现。
 *
 * <p>{@code MinecraftServer.execute(Runnable)} 走 MC 真实事件循环；反射调用私有钩子时
 * {@code runningTask()} 为 false，任务被直接同步执行，可确定性地观测副作用。
 */
public final class MinecraftTestSupport {

    private MinecraftTestSupport() {
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

    /** 造一个服务端替身：真实事件循环、空玩家表、可注入维度表。 */
    public static MinecraftServer newServer() {
        MinecraftServer server = allocateInstance(DedicatedServer.class);
        set(server, "serverThread", Thread.currentThread());
        setIfPresent(server, "profiler", net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
        set(server, "levels", new java.util.concurrent.ConcurrentHashMap<>());
        // 事件循环队列由构造函数初始化；跳过构造函数后须补上，execute(Runnable) 才能执行任务
        setField(
                server,
                field(BlockableEventLoop.class, "pendingRunnables"),
                new java.util.ArrayDeque<>());
        return server;
    }

    /**
     * 执行并清空事件循环中已排队的任务，返回执行数量。
     *
     * <p>按"待执行数量"驱动而非依赖 {@code pollTask()} 的返回值：队列空时的探测分支会触碰
     * 服务端 tick 速率管理器等运行期对象，替身无需补齐这些字段。
     */
    public static int drainTasks(MinecraftServer server) {
        return drainTasks((Object) server);
    }

    /** 反射调用受保护的 tick 任务执行入口（各 MC 版本方法名一致、可见性不同）。 */
    private static boolean pollTask(Object eventLoop) {
        try {
            java.lang.reflect.Method poll =
                    net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredMethod("pollTask");
            poll.setAccessible(true);
            return (Boolean) poll.invoke(eventLoop);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法驱动事件循环", e);
        }
    }

    /** 执行并清空给定事件循环（服务端 / 客户端均适用）已排队的任务。 */
    public static int drainTasks(Object eventLoop) {
        int executed = 0;
        while (pendingTasks(eventLoop) > 0 && pollTask(eventLoop)) {
            executed++;
        }
        return executed;
    }

    private static int pendingTasks(Object eventLoop) {
        return ((net.minecraft.util.thread.BlockableEventLoop<?>) eventLoop).getPendingTasksCount();
    }

    /** 造一个玩家替身：注入 UUID 与游戏档案，使 {@code getUUID()} / {@code getName()} 可用。 */
    public static ServerPlayer newPlayer(UUID playerId, String name) {
        ServerPlayer player = allocateInstance(ServerPlayer.class);
        seedPlayer(player, playerId, name);
        return player;
    }

    /** 向已分配的玩家替身（或其子类）写入身份字段，供需要覆写行为的测试复用。 */
    public static void seedPlayer(ServerPlayer player, UUID playerId, String name) {
        setField(player, uuidField(), playerId);
        set(player, "gameProfile", new com.mojang.authlib.GameProfile(playerId, name));
    }

    /** 造一个世界替身，仅注入维度键（供世界端口查询）。 */
    public static ServerLevel newLevel(String dimensionId) {
        ServerLevel level = allocateInstance(ServerLevel.class);
        setField(level, field(Level.class, "dimension"), dimensionKey(dimensionId));
        return level;
    }

    /** 把世界替身装入服务端替身的维度表。 */
    public static void putLevel(MinecraftServer server, ServerLevel level) {
        levels(server).put(dimensionKey(level.dimension().identifier().toString()), level);
    }

    /** 把玩家表装入服务端替身（含 UUID 索引与在线列表）。 */
    public static void installPlayerList(MinecraftServer server, ServerPlayer... players) {
        PlayerList playerList = allocateInstance(DedicatedPlayerList.class);
        set(playerList, "server", server);
        java.util.List<ServerPlayer> online = new java.util.ArrayList<>();
        Map<UUID, ServerPlayer> byId = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            online.add(player);
            byId.put(player.getUUID(), player);
        }
        set(playerList, "players", online);
        set(playerList, "playersByUUID", byId);
        server.setPlayerList(playerList);
    }

    /** 取玩家的连接句柄（与产品栈同构）。 */
    public static FabricConnectionHandle handleOf(ServerPlayer player) {
        return new FabricConnectionHandle(player);
    }

    /** 用 {@link Unsafe} 跳过构造函数分配实例（随后用 {@link #set} 注入所需字段）。 */
    public static <T> T allocateInstance(Class<T> type) {
        bootstrapMinecraft();
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafe.get(null);
            return type.cast(unsafe.allocateInstance(type));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法分配 " + type.getName(), e);
        }
    }

    /** 按字段声明类型造一个同类型的空列表（兼容 1.20 的 List 与 1.21+ 的 ArrayListDeque）。 */
    public static Object newListLike(String fieldName, Object owner) {
        Class<?> declared = field(owner.getClass(), fieldName).getType();
        if (declared.isInterface() || declared == java.util.List.class) {
            return new java.util.ArrayList<>();
        }
        try {
            return declared.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造 " + declared.getName(), e);
        }
    }

    public static void set(Object target, String fieldName, Object value) {
        setField(target, field(target.getClass(), fieldName), value);
    }

    /** 字段存在时写入（跨版本字段增删时的兼容分支）。 */
    public static void setIfPresent(Object target, String fieldName, Object value) {
        Field field = findField(target.getClass(), fieldName);
        if (field != null) {
            setField(target, field, value);
        }
    }

    /** 查找字段，不存在返回 {@code null}。 */
    public static Field findField(Class<?> type, String name) {
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
        return null;
    }

    /** 逐级向上查找字段（含父类私有字段）。 */
    public static Field field(Class<?> type, String name) {
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
        throw new IllegalStateException("找不到字段 " + type.getName() + "#" + name);
    }

    public static void setField(Object target, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("写入字段失败 " + field, e);
        }
    }

    /** 读取静态字段。 */
    public static Object staticField(Class<?> owner, String fieldName) {
        try {
            Field field = field(owner, fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取静态字段失败 " + owner.getName() + "#" + fieldName, e);
        }
    }

    public static Object getField(Object target, Field field) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取字段失败 " + field, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> levels(MinecraftServer server) {
        return (Map<Object, Object>) getField(server, field(MinecraftServer.class, "levels"));
    }

    private static ResourceKey<Level> dimensionKey(String dimensionId) {
        return ResourceKey.create(Registries.DIMENSION, resourceLocation(dimensionId));
    }

    /** 按 {@code namespace:path} 构造资源标识（沿用产品侧的跨版本构造工具）。 */
    public static Identifier resourceLocation(String resourceId) {
        int split = resourceId.indexOf(':');
        String namespace = split < 0 ? "minecraft" : resourceId.substring(0, split);
        String path = split < 0 ? resourceId : resourceId.substring(split + 1);
        return FabricResourceLocations.of(namespace, path);
    }

    /** {@link Entity#getUUID()} 的数据来源字段（各 MC 版本一致，位于 Entity 基类）。 */
    private static Field uuidField() {
        return field(Entity.class, "uuid");
    }
}
