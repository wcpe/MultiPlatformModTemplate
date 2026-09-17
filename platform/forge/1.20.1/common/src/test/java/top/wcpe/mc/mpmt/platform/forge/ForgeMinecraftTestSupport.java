package top.wcpe.mc.mpmt.platform.forge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.InactiveProfiler;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeServerTransport;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeServerNetwork;
import top.wcpe.mc.mpmt.platform.forge.version.v1_20.V1_20ServerNetwork;

/**
 * Forge 1.20.1 平台测试的 Minecraft 基座：纯 JVM 下造出可用的服务端 / 玩家 / 世界替身。
 *
 * <p>手法沿用仓库既有约定：{@code Bootstrap.bootStrap()} 初始化 MC 注册表；{@code Unsafe.allocateInstance}
 * 跳过构造函数造实例；反射写入 final 字段。
 *
 * <p><b>引导边界</b>：Forge 47 的 {@code Bootstrap.bootStrap()} 末段会经 {@code NetworkHooks.init}
 * 触发 FML 网络握手栈初始化，而该栈在纯 JVM（无 ModLauncher 变换）下必然失败——此时 MC 注册表引导<b>已经完成</b>，
 * 故本基座吞掉该失败并继续。
 */
public final class ForgeMinecraftTestSupport {

    private ForgeMinecraftTestSupport() {
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
     * <p>{@code MinecraftServer#execute} 在调用线程即服务端线程时会直接同步执行；标记为外来线程后
     * 任务会真正进入事件循环队列，从而可断言"提交到服务端线程"的转发语义。
     */
    public static MinecraftServer newForeignThreadServer() {
        MinecraftServer server = newServer();
        Thread foreign = new Thread(() -> { }, "mpmt-测试服务端线程");
        set(server, "serverThread", foreign);
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

    /**
     * 把 {@code FMLPaths.CONFIGDIR} 指向给定目录（该枚举常量持有可写非 final 的绝对路径字段）。
     *
     * <p>{@code FMLPaths} 枚举常量持有非 final 的 {@code absolutePath} 字段，直接写入即可；
     * 枚举自身的类初始化在纯 JVM 下会给出 null 路径，故必须在注入后才读取 {@code get()}。
     */
    public static void replaceConfigDirectory(Path directory) {
        bootstrapMinecraft();
        try {
            Class<?> paths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
            Field constant = paths.getField("CONFIGDIR");
            Object configDir = constant.get(null);
            set(configDir, "absolutePath", directory);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge 路径枚举", error);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法指向测试配置目录", error);
        }
    }

    /** 造一个挂在替身网络适配器上的服务端传输：通道路径唯一化，避免 Forge 通道注册表撞键。 */
    public static ForgeServerTransport newTransport() {
        return new ForgeServerTransport(new V1_20ServerNetwork("mpmt", "test-" + UUID.randomUUID()));
    }

    /** 取出并在当前线程执行服务端替身事件循环里已排队的任务（使调度端口转发可确定性断言）。 */
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

    /**
     * 把 Forge 运行期版本元数据替换为给定 Minecraft / Forge 版本。
     *
     * <p>{@code FMLLoader.versionInfo} 在纯 JVM 下为 null，而产品入口的版本探测直接读它。
     */
    public static void installVersionInfo(String minecraftVersion, String forgeVersion) {
        bootstrapMinecraft();
        try {
            Class<?> versionInfo = Class.forName("net.minecraftforge.fml.loading.VersionInfo");
            Object info = versionInfo
                    .getDeclaredConstructor(String.class, String.class, String.class, String.class)
                    .newInstance(forgeVersion, minecraftVersion, minecraftVersion, "net.minecraftforge");
            setStatic(Class.forName("net.minecraftforge.fml.loading.FMLLoader"), "versionInfo", info);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge 版本元数据类型", error);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法安装 Forge 版本元数据", error);
        }
    }

    /**
     * 清空 Forge 通道注册表：该表为进程级静态状态，而产品通道固定为 {@code mpmt:main}，
     * 多次构造产品入口会撞键（{@code NetworkDirection Channel already registered}）。
     */
    public static void resetNetworkRegistry() {
        try {
            Class<?> registry = Class.forName("net.minecraftforge.network.NetworkRegistry");
            setStatic(registry, "instances", new HashMap<Object, Object>());
            setStatic(registry, "lock", Boolean.FALSE);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到 Forge 网络注册表", error);
        }
    }

    /**
     * 装一个空 mod 表：使 {@code ModList.get()} 可用、{@code getModContainerById} 一律返回空。
     *
     * <p>用于覆盖"未打包版本信息时回退为 unknown"的产品分支。
     */
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

    /**
     * 把客户端 {@code Minecraft} 单例替换为替身：注入 HUD 渲染与产品收发链路所需的全部子对象，
     * 并使 {@code getInstance().execute(...)} 可在测试线程排空。
     */
    public static Object installClientInstance() {
        bootstrapMinecraft();
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = allocateInstance(minecraftClass);
            set(minecraft, "pendingRunnables", new java.util.ArrayDeque<Runnable>());

            net.minecraft.client.gui.Gui gui = allocateInstance(net.minecraft.client.gui.Gui.class);
            set(gui, "chat", clientChatComponent(minecraft));
            set(minecraft, "gui", gui);
            set(minecraft, "toast", newToastComponent());
            // getConnection() 走 Minecraft#player.connection，故必须注入本地玩家与监听器
            net.minecraft.network.Connection connection = newClientConnection();
            net.minecraft.client.multiplayer.ClientPacketListener listener = newClientListener(connection);
            set(minecraft, "pendingConnection", connection);
            set(minecraft, "level", newClientLevel(listener));
            set(minecraft, "player", newLocalPlayer(listener));
            set(minecraft, "options", newClientOptions());
            set(minecraft, "font", newClientFont());
            setStatic(minecraftClass, "instance", minecraft);
            return minecraft;
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到客户端主类", error);
        }
    }

    /** 客户端连接监听器：用于断言出站报文真实经过产品通道。 */
    public static net.minecraft.client.multiplayer.ClientPacketListener clientListener() {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            return (net.minecraft.client.multiplayer.ClientPacketListener)
                    minecraftClass.getMethod("getConnection").invoke(clientMinecraft());
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到客户端主类", error);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法取得客户端连接", error);
        }
    }

    /** 取客户端代理所绑定的客户端传输通道。 */
    public static ResourceLocation channelOf(Object clientProxy) {
        Object transport = getField(clientProxy, field(clientProxy.getClass(), "transport"));
        return (ResourceLocation) getField(transport, field(transport.getClass(), "channel"));
    }

    /**
     * 清空客户端代理的活跃特性持有者，用于覆盖"未初始化明确失败"分支。
     *
     * <p>{@code ACTIVE_FEATURE} 为 {@code static final} 的 {@code AtomicReference}：改写引用被 final 语义拒绝，
     * 故直接把其持有的内容清空（等价于"尚未装配"）。
     */
    public static void clearClientFeature(Object clientProxy) {
        Object holder = getField(null, field(clientProxy.getClass(), "ACTIVE_FEATURE"));
        ((java.util.concurrent.atomic.AtomicReference<?>) holder).set(null);
    }

    /** 排空客户端替身已排队的任务（使 {@code Minecraft#execute} 提交的渲染任务可确定性观测）。 */
    public static int drainClientTasks() {
        Object minecraft = clientMinecraft();
        @SuppressWarnings("unchecked")
        java.util.Queue<Runnable> pending =
                (java.util.Queue<Runnable>) getField(minecraft, field(minecraft.getClass(), "pendingRunnables"));
        int executed = 0;
        Runnable task = pending.poll();
        while (task != null) {
            task.run();
            executed++;
            task = pending.poll();
        }
        return executed;
    }

    private static Object clientMinecraft() {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            return minecraftClass.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到客户端主类", error);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法取得客户端单例", error);
        }
    }

    /** 造一个可用的聊天组件替身（{@code addMessage} 所需的集合与主类引用全部注入）。 */
    private static net.minecraft.client.gui.components.ChatComponent clientChatComponent(Object minecraft) {
        net.minecraft.client.gui.components.ChatComponent chat =
                allocateInstance(net.minecraft.client.gui.components.ChatComponent.class);
        set(chat, "minecraft", minecraft);
        set(chat, "recentChat", new ArrayList<Object>());
        set(chat, "allMessages", new ArrayList<Object>());
        set(chat, "trimmedMessages", new ArrayList<Object>());
        set(chat, "messageDeletionQueue", new ArrayList<Object>());
        return chat;
    }

    /** 造一个可用的提示组件替身。 */
    private static net.minecraft.client.gui.components.toasts.ToastComponent newToastComponent() {
        net.minecraft.client.gui.components.toasts.ToastComponent toasts =
                allocateInstance(net.minecraft.client.gui.components.toasts.ToastComponent.class);
        set(toasts, "queued", new java.util.ArrayDeque<Object>());
        return toasts;
    }

    /** 造一个客户端连接替身：底层挂内嵌 Netty 通道与发包队列，可真实编码发包。 */
    private static net.minecraft.network.Connection newClientConnection() {
        net.minecraft.network.Connection connection = allocateInstance(net.minecraft.network.Connection.class);
        set(connection, "channel", new io.netty.channel.embedded.EmbeddedChannel());
        set(connection, "address", new java.net.InetSocketAddress("127.0.0.1", 25566));
        set(connection, "queue", new java.util.concurrent.ConcurrentLinkedQueue<Object>());
        return connection;
    }

    /** 造一个客户端连接监听器替身并绑定底层连接。 */
    private static net.minecraft.client.multiplayer.ClientPacketListener newClientListener(
            net.minecraft.network.Connection connection) {
        net.minecraft.client.multiplayer.ClientPacketListener listener =
                allocateInstance(net.minecraft.client.multiplayer.ClientPacketListener.class);
        set(listener, "connection", connection);
        return listener;
    }

    /** 造一个客户端世界替身：仅注入连接监听器。 */
    private static net.minecraft.client.multiplayer.ClientLevel newClientLevel(
            net.minecraft.client.multiplayer.ClientPacketListener listener) {
        net.minecraft.client.multiplayer.ClientLevel level =
                allocateInstance(net.minecraft.client.multiplayer.ClientLevel.class);
        set(level, "connection", listener);
        return level;
    }

    /** 造一个本地玩家替身：仅注入连接监听器，使 {@code Minecraft#getConnection()} 可用。 */
    private static net.minecraft.client.player.LocalPlayer newLocalPlayer(
            net.minecraft.client.multiplayer.ClientPacketListener listener) {
        net.minecraft.client.player.LocalPlayer player =
                allocateInstance(net.minecraft.client.player.LocalPlayer.class);
        set(player, "connection", listener);
        return player;
    }

    /** 造一个客户端选项替身：所有选项按泛型填默认值，满足 HUD 渲染的度量需求。 */
    private static net.minecraft.client.Options newClientOptions() {
        net.minecraft.client.Options options = allocateInstance(net.minecraft.client.Options.class);
        for (Field field : options.getClass().getDeclaredFields()) {
            if (field.getType() != net.minecraft.client.OptionInstance.class) {
                continue;
            }
            java.lang.reflect.Type generic = field.getGenericType();
            boolean flag = generic instanceof java.lang.reflect.ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] == Boolean.class;
            setField(options, field, flag ? optionOf(Boolean.TRUE) : optionOf(1.0D));
        }
        return options;
    }

    /** 造一个 {@code OptionInstance} 替身并写入固定值（{@code get()} 直读私有 value 字段）。 */
    private static <T> net.minecraft.client.OptionInstance<T> optionOf(T value) {
        // 替身绕过构造器分配，类字面量拿不到泛型实参；此处按调用方约定填默认值，故就地抑制。
        @SuppressWarnings("unchecked")
        net.minecraft.client.OptionInstance<T> option = allocateInstance(net.minecraft.client.OptionInstance.class);
        set(option, "value", value);
        return option;
    }

    /** 造一个客户端字体替身：注入宽度度量器，使文本分割可用（不触及纹理资源）。 */
    private static net.minecraft.client.gui.Font newClientFont() {
        net.minecraft.client.gui.Font font = allocateInstance(net.minecraft.client.gui.Font.class);
        set(font, "splitter", newSplitter());
        return font;
    }

    /** 构造宽度度量器：用动态代理适配 {@code StringSplitter$WidthProvider}，避免版本签名差异。 */
    private static net.minecraft.client.StringSplitter newSplitter() {
        try {
            Class<?> providerType = Class.forName("net.minecraft.client.StringSplitter$WidthProvider");
            Object provider = java.lang.reflect.Proxy.newProxyInstance(
                    providerType.getClassLoader(),
                    new Class<?>[] {providerType},
                    (proxy, invoked, arguments) -> {
                        Object codepoint = arguments == null || arguments.length == 0 ? null : arguments[0];
                        return codepoint instanceof Integer value ? value == 0 ? 0.0F : 6.0F : 0.0F;
                    });
            return new net.minecraft.client.StringSplitter(
                    (net.minecraft.client.StringSplitter.WidthProvider) provider);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("找不到宽度度量类型", error);
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

    /**
     * 写入静态字段（Forge 的 {@code FMLLoader.versionInfo} / {@code ModList.INSTANCE} 均非 final）。
     */
    public static void setStatic(Class<?> owner, String name, Object value) {
        try {
            Field target = field(owner, name);
            if (target.getType() == Class.class) {
                throw new IllegalStateException("拒绝写入 Class 字段：" + name);
            }
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

    /** 假 L4 网络适配器：把通道收发与句柄构造完全替身化，使 L3 传输逻辑可独立断言。 */
    public static final class FakeServerNetwork implements ForgeServerNetwork {

        private final ResourceLocation channelId = resourceLocation("mpmt:fake-" + UUID.randomUUID());
        private final java.util.List<byte[]> sent = new java.util.ArrayList<>();
        private java.util.function.BiConsumer<ServerPlayer, byte[]> receiver;

        @Override
        public ResourceLocation channelId() {
            return channelId;
        }

        @Override
        public void registerReceiver(java.util.function.BiConsumer<ServerPlayer, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public void send(ServerPlayer player, byte[] data) {
            sent.add(data);
        }

        @Override
        public top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle connectionOf(ServerPlayer player) {
            return new ForgeConnectionHandle(player);
        }

        @Override
        public int maxPayloadSize() {
            return 4096;
        }

        /** 已发送载荷快照。 */
        public java.util.List<byte[]> sentPayloads() {
            return java.util.List.copyOf(sent);
        }

        /** 模拟一次入站收包（要求已注册收包器）。 */
        public void receive(ServerPlayer sender, byte[] data) {
            if (receiver == null) {
                throw new IllegalStateException("尚未注册收包器");
            }
            receiver.accept(sender, data);
        }
    }

    private static ResourceKey<net.minecraft.world.level.Level> dimensionKey(String dimensionId) {
        return ResourceKey.create(Registries.DIMENSION, resourceLocation(dimensionId));
    }

    /** 由 {@code namespace:path} 解析资源位置；两参构造器被映射标记为待删除，语义等价故就地抑制。 */
    @SuppressWarnings("removal")
    private static ResourceLocation resourceLocation(String dimensionId) {
        int split = dimensionId.indexOf(':');
        if (split < 0) {
            return ResourceLocation.withDefaultNamespace(dimensionId);
        }
        return new ResourceLocation(dimensionId.substring(0, split), dimensionId.substring(split + 1));
    }
}
