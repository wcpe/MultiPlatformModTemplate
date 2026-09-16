package top.wcpe.mc.mpmt.platform.sponge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.Game;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandExecutor;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.command.parameter.managed.standard.ResourceKeyedValueParameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.EventManager;
import org.spongepowered.api.network.ServerPlayerConnection;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.api.network.channel.raw.play.RawPlayDataChannel;
import org.spongepowered.api.registry.BuilderProvider;import org.spongepowered.api.registry.DefaultedRegistryReference;
import org.spongepowered.api.registry.DefaultedRegistryType;
import org.spongepowered.api.registry.FactoryProvider;
import org.spongepowered.api.registry.RegistryHolder;
import org.spongepowered.api.registry.RegistryKey;
import org.spongepowered.api.registry.RegistryType;
import org.spongepowered.api.scheduler.ScheduledTask;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.ResettableBuilder;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.api.world.server.WorldManager;
import org.spongepowered.plugin.PluginContainer;

/**
 * SpongeAPI 静态 Holder（{@link Sponge#game()}）的测试替身装配 + 平台对象动态代理工具。
 *
 * <p>Sponge 车道的端口实现全部"委托给 Sponge API 接口"，真实实现须整套服务端才能构造。本类把假 {@link Game}
 * 反射写入 {@code Sponge} 的私有静态字段 {@code game}，此后 {@code Sponge.server()} /
 * {@code Sponge.asyncScheduler()} / {@code Sponge.platform()} / {@code Sponge.eventManager()} 等静态入口
 * 均返回记录了调用的替身，端口实现即可在纯 JVM 下被测。
 *
 * <p>假调度器只"受理"任务、不重入执行：确定性交给测试按 {@link #runSyncTasks()} / {@link #runAsyncTasks()}
 * 显式驱动，避免替身内部异步导致断言竞态。
 *
 * <p>用 try-with-resources 保证用完还原原字段值，不污染同 JVM 的其它测试。
 */
public final class SpongeTestRuntime implements AutoCloseable {

    /** 同步调度器受理的一次性任务体。 */
    public final List<Runnable> syncTasks = new ArrayList<>();
    /** 异步调度器受理的任务体。 */
    public final List<Runnable> asyncTasks = new ArrayList<>();
    /** 经 {@link Task.Builder} 构建出的任务，供观察插件归属与延迟 / 周期。 */
    public final List<Task> builtTasks = new ArrayList<>();
    /** 周期调度受理记录。 */
    public final List<ScheduledRunnable> timers = new ArrayList<>();
    /** 周期调度返回的句柄，供断言取消。 */
    public final List<ScheduledTask> timerHandles = new ArrayList<>();
    /** 事件监听器注册记录。 */
    public final List<Object> registeredListeners = new ArrayList<>();
    /** 已加载世界（按声明顺序）。 */
    public final List<ServerWorld> worlds = new ArrayList<>();
    /** 在线玩家（按 UUID，保持插入顺序）。 */
    public final Map<UUID, ServerPlayer> onlinePlayers = new LinkedHashMap<>();
    /** 玩家被 kick 的 UUID → 理由文本。 */
    public final Map<UUID, String> kicks = new LinkedHashMap<>();
    /** 玩家收到的消息（{@code UUID → 组件文本}，按投递顺序）。 */
    public final Map<UUID, List<String>> playerMessages = new LinkedHashMap<>();
    /** 经原始数据通道派发到玩家的负载长度（按投递顺序）。 */
    public final List<Integer> dispatchedPayloads = new ArrayList<>();
    /** 平台报告的 Minecraft 版本。 */
    public String minecraftVersion = "1.20.1";
    /** {@code server().onMainThread()} 的返回值。 */
    public boolean mainThread = true;
    /** 周期任务句柄 {@code cancel()} 的返回值。 */
    public boolean cancelResult = true;
    /** 玩家 {@code kick(Component)} 的返回值。 */
    public boolean kickResult = true;

    private final Object previousGame;
    private final Map<UUID, Object> cancelledTimers = new LinkedHashMap<>();
    private final Map<Task, TaskState> taskStates = new IdentityHashMap<>();
    private final Map<Object, String> parameterKeys = new IdentityHashMap<>();

    /** 一次周期调度的记录。 */
    public static final class ScheduledRunnable {
        /** 首次延迟 tick 数。 */
        public final long delayTicks;
        /** 周期 tick 数。 */
        public final long periodTicks;
        /** 任务体。 */
        public final Runnable body;

        ScheduledRunnable(long delayTicks, long periodTicks, Runnable body) {
            this.delayTicks = delayTicks;
            this.periodTicks = periodTicks;
            this.body = body;
        }
    }

    private SpongeTestRuntime(Object previousGame) {
        this.previousGame = previousGame;
    }

    /** 装配假 Game（先保存原字段值以便还原）。 */
    public static SpongeTestRuntime install() {
        SpongeTestRuntime holder = new SpongeTestRuntime(readGameField());
        writeGameField(holder.newGame());
        return holder;
    }

    /** 静态 Sponge 是否已被本替身替换（用例内应为真，收尾 close 后还原为原值）。 */
    public boolean isInstalled() {
        Object current = readGameField();
        return current != null && System.identityHashCode(current) != System.identityHashCode(previousGame);
    }

    @Override
    public void close() {
        writeGameField(previousGame);
    }

    /** 清空已受理的同步任务，按受理顺序执行。 */
    public void runSyncTasks() {
        List<Runnable> pending = new ArrayList<>(syncTasks);
        syncTasks.clear();
        pending.forEach(Runnable::run);
    }

    /** 清空已受理的异步任务，按受理顺序执行。 */
    public void runAsyncTasks() {
        List<Runnable> pending = new ArrayList<>(asyncTasks);
        asyncTasks.clear();
        pending.forEach(Runnable::run);
    }

    /** 造一个指定 UUID / 名称的在线玩家并登记进 {@code Sponge.server().player(...)}。 */
    public ServerPlayer addPlayer(UUID playerId, String name) {
        ServerPlayer player =
                proxy(
                        ServerPlayer.class,
                        (source, method, args) -> {
                            switch (method.getName()) {
                                case "uniqueId":
                                    return playerId;
                                case "name":
                                    return name;
                                case "kick":
                                    kicks.put(playerId, String.valueOf(args[0]));
                                    return kickResult;
                                case "sendMessage":
                                    playerMessages
                                            .computeIfAbsent(playerId, key -> new ArrayList<>())
                                            .add(String.valueOf(args[args.length - 1]));
                                    return null;
                                default:
                                    return defaultResult(source, method, args);
                            }
                        });
        onlinePlayers.put(playerId, player);
        return player;
    }

    /** 造一个指定资源键字符串（{@code formatted()} 结果）的服务端世界。 */    public ServerWorld addWorld(String formattedKey) {
        ServerWorld world =
                stub(
                        ServerWorld.class,
                        results("key", stub(org.spongepowered.api.ResourceKey.class, results("formatted", formattedKey))));
        worlds.add(world);
        return world;
    }

    /** 清空在线玩家（模拟玩家离线）。 */
    public void clearPlayers() {
        onlinePlayers.clear();
    }

    /**
     * 造一个命令上下文：{@code cause} 由调用方给出，参数值按声明名寻址，发送的组件收集到 {@code messages}。
     *
     * @param cause       命令起因（决定玩家归属）
     * @param valuesByName 参数声明名 → 已解析值
     * @param messages    接收 {@code sendMessage} 的收集器
     */
    public CommandContext context(
            CommandCause cause, Map<String, String> valuesByName, List<Component> messages) {
        return proxy(
                CommandContext.class,
                (source, method, args) -> {
                    switch (method.getName()) {
                        case "cause":
                            return cause;
                        case "sendMessage":
                            messages.add((Component) args[args.length - 1]);
                            return null;
                        case "requireOne":
                        case "one":
                            return resolveParameter(method, args[0], valuesByName);
                        default:
                            return defaultResult(source, method, args);
                    }
                });
    }

    /** 造一个归属指定玩家的命令起因；{@code first(ServerPlayer.class)} 返回该玩家。 */
    public static CommandCause playerCause(ServerPlayer player) {
        return proxy(
                CommandCause.class,
                (source, method, args) ->
                        method.getName().equals("first") && ServerPlayer.class.equals(args[0])
                                ? Optional.of(player)
                                : defaultResult(source, method, args));
    }

    /** 造一个无玩家归属（控制台）的命令起因；{@code first(...)} 恒空。 */
    public static CommandCause consoleCause() {
        return stub(CommandCause.class, results("first", Optional.empty()));
    }

    /** 造一个空的插件容器替身。 */
    public static PluginContainer pluginContainer() {
        return stub(PluginContainer.class, results());
    }

    /** 造一个原始数据通道替身；{@code sendTo} 的负载长度记入 {@link #dispatchedPayloads}。 */
    public RawDataChannel rawDataChannel() {
        RawPlayDataChannel play =
                proxy(
                        RawPlayDataChannel.class,
                        (source, method, args) -> {
                            if (method.getName().equals("sendTo")) {
                                recordPayload(args);
                            }
                            return defaultResult(source, method, args);
                        });
        return stub(RawDataChannel.class, results("play", play));
    }

    /** 从 {@code sendTo} 参数中取出负载并记录长度（第 2 参为写入器，需实际调用才能取得字节）。 */
    private void recordPayload(Object[] args) {
        if (args.length < 2 || !(args[1] instanceof java.util.function.Consumer)) {
            dispatchedPayloads.add(-1);
            return;
        }
        final int[] length = {-1};
        ChannelBuf buf =
                proxy(
                        ChannelBuf.class,
                        (source, method, args1) -> {
                            if (method.getName().equals("writeBytes") && args1[0] instanceof byte[]) {
                                length[0] = ((byte[]) args1[0]).length;
                            }
                            return source;
                        });
        try {
            castConsumer(args[1]).accept(buf);
        } catch (RuntimeException error) {
            dispatchedPayloads.add(-2);
            return;
        }
        dispatchedPayloads.add(length[0]);
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Consumer<ChannelBuf> castConsumer(Object value) {
        return (java.util.function.Consumer<ChannelBuf>) value;
    }

    /** 造 {@code 玩家 → 物理连接} 的一对一替身。 */
    public static ServerPlayerConnection playerConnection(ServerPlayer player) {
        return stub(ServerPlayerConnection.class, results("player", player));
    }

    /** 造一个绑定了命令执行器的参数化命令替身。 */
    public static Command.Parameterized parameterized(CommandExecutor executor) {
        return stub(
                Command.Parameterized.class,
                results("executor", Optional.of(executor), "parameters", new ArrayList<Object>()));
    }

    /** 取方法返回值类型的接口默认值：引用类型 {@code null}、基本类型零值。 */
    public static Object defaultResult(Object source, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return objectResult(source, method, args);
        }
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }

    /** {@link Object} 三方法在代理上的合理实现。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public static Object objectResult(Object source, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return source == args[0];
            case "hashCode":
                return System.identityHashCode(source);
            case "toString":
                return source.getClass().getInterfaces().length == 0
                        ? "Sponge 测试替身"
                        : source.getClass().getInterfaces()[0].getSimpleName() + "替身";
            default:
                return null;
        }
    }

    /** 按接口造动态代理。 */
    public static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    /**
     * 造一个"命中名即返回固定值、其余走默认值"的替身：值若为 {@link Supplier} 则每次调用取 {@code get()}。
     *
     * @param type   接口类型
     * @param results 方法名 → 固定值
     */
    public static <T> T stub(Class<T> type, Map<String, Object> results) {
        return proxy(
                type,
                (source, method, args) -> {
                    if (!results.containsKey(method.getName())) {
                        return defaultResult(source, method, args);
                    }
                    Object value = results.get(method.getName());
                    return value instanceof Supplier ? ((Supplier<?>) value).get() : value;
                });
    }

    /** 造一个可变参数形式的结果表（名 / 值成对出现）。 */
    public static Map<String, Object> results(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private Object resolveParameter(Method method, Object parameter, Map<String, String> valuesByName) {
        String name = parameterKeys.get(parameter);
        String value = name == null ? null : valuesByName.get(name);
        if (Optional.class.equals(method.getReturnType())) {
            return Optional.ofNullable(value);
        }
        if (value == null) {
            throw new java.util.NoSuchElementException("缺少命令参数：" + name);
        }
        return value;
    }

    private Object newGame() {
        EventManager eventManager =
                proxy(
                        EventManager.class,
                        (source, method, args) -> {
                            if (method.getName().startsWith("register")) {
                                registeredListeners.add(args[args.length - 1]);
                                return source;
                            }
                            return defaultResult(source, method, args);
                        });
        return stub(
                Game.class,
                results(
                        "server", newServer(),
                        "asyncScheduler", newAsyncScheduler(),
                        "eventManager", eventManager,
                        "isServerAvailable", true,
                        "platform", newPlatform(),
                        "builderProvider", newBuilderProvider(),
                        "factoryProvider", newFactoryProvider()));
    }

    private Object newPlatform() {
        return stub(
                org.spongepowered.api.Platform.class,
                results(
                        "minecraftVersion",
                        (Supplier<Object>) () ->
                                stub(
                                        org.spongepowered.api.MinecraftVersion.class,
                                        results("name", minecraftVersion))));
    }

    private Server newServer() {
        WorldManager worldManager =
                stub(WorldManager.class, results("worlds", (Supplier<List<ServerWorld>>) () -> new ArrayList<>(worlds)));
        return proxy(
                Server.class,
                (source, method, args) -> {
                    switch (method.getName()) {
                        case "scheduler":
                            return newSyncScheduler();
                        case "worldManager":
                            return worldManager;
                        case "onMainThread":
                            return mainThread;
                        case "player":
                            return Optional.ofNullable(onlinePlayers.get((UUID) args[0]));
                        case "onlinePlayers":
                            return new ArrayList<>(onlinePlayers.values());
                        case "streamOnlinePlayers":
                            return new ArrayList<>(onlinePlayers.values()).stream();
                        default:
                            return defaultResult(source, method, args);
                    }
                });
    }

    private Scheduler newSyncScheduler() {
        return proxy(
                Scheduler.class,
                (source, method, args) ->
                        method.getName().equals("submit") && args[0] instanceof Task
                                ? acceptSync((Task) args[0])
                                : defaultResult(source, method, args));
    }

    private Scheduler newAsyncScheduler() {
        return proxy(
                Scheduler.class,
                (source, method, args) -> {
                    if (!method.getName().equals("submit") || !(args[0] instanceof Task)) {
                        return defaultResult(source, method, args);
                    }
                    Task task = (Task) args[0];
                    TaskState state = taskStates.get(task);
                    if (state != null && state.body != null) {
                        asyncTasks.add(state.body);
                    }
                    return newScheduledTask(task, false);
                });
    }

    private Object acceptSync(Task task) {
        TaskState state = taskStates.get(task);
        if (state == null) {
            return newScheduledTask(task, false);
        }
        if (state.periodTicks >= 0L) {
            timers.add(new ScheduledRunnable(state.delayTicks, state.periodTicks, state.body));
            return newScheduledTask(task, true);
        }
        if (state.body != null) {
            syncTasks.add(state.body);
        }
        return newScheduledTask(task, false);
    }

    private ScheduledTask newScheduledTask(Task task, boolean cancellable) {
        UUID id = UUID.randomUUID();
        ScheduledTask handle =
                proxy(
                        ScheduledTask.class,
                        (source, method, args) -> {
                            switch (method.getName()) {
                                case "cancel":
                                    cancelledTimers.put(id, Boolean.TRUE);
                                    return cancelResult;
                                case "isCancelled":
                                    return Boolean.TRUE.equals(cancelledTimers.get(id));
                                case "task":
                                    return task;
                                case "uniqueId":
                                    return id;
                                default:
                                    return defaultResult(source, method, args);
                            }
                        });
        if (cancellable) {
            timerHandles.add(handle);
        }
        return handle;
    }

    private BuilderProvider newBuilderProvider() {
        return new BuilderProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ResettableBuilder<?, ? super T>> T provide(Class<T> type) {
                if (Task.Builder.class.equals(type)) {
                    return (T) newTaskBuilder();
                }
                if (Command.Builder.class.equals(type)) {
                    return (T) newCommandBuilder();
                }
                throw new IllegalStateException("测试替身未覆盖的构建器类型：" + type.getName());
            }
        };
    }

    private FactoryProvider newFactoryProvider() {
        return new FactoryProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T provide(Class<T> type) {
                if (Parameter.Factory.class.equals(type)) {
                    return (T) newParameterFactory();
                }
                if (CommandResult.Factory.class.equals(type)) {
                    return (T) COMMAND_RESULT_FACTORY;
                }
                if (org.spongepowered.api.ResourceKey.Factory.class.equals(type)) {
                    return (T) RESOURCE_KEY_FACTORY;
                }
                if (Ticks.Factory.class.equals(type)) {
                    return (T) TICKS_FACTORY;
                }
                if (RegistryType.Factory.class.equals(type)) {
                    return (T) REGISTRY_TYPE_FACTORY;
                }
                if (RegistryKey.Factory.class.equals(type)) {
                    return (T) REGISTRY_KEY_FACTORY;
                }
                throw new IllegalStateException("测试替身未覆盖的工厂类型：" + type.getName());
            }
        };
    }

    private Parameter.Factory newParameterFactory() {
        return new Parameter.Factory() {
            @Override
            public <T> Parameter.Value.Builder<T> createParameterBuilder(Parameter.Key<T> key) {
                return newParameterBuilder();
            }

            @Override
            public <T> Parameter.Value.Builder<T> createParameterBuilder(
                    io.leangen.geantyref.TypeToken<T> parameterClass) {
                return newParameterBuilder();
            }

            @Override
            public <T> Parameter.Value.Builder<T> createParameterBuilder(Class<T> parameterClass) {
                return newParameterBuilder();
            }
        };
    }

    /** 造参数构建器：记录 {@code key(name)} / {@code optional()}，{@code build()} 产出带名参数。 */
    private <T> Parameter.Value.Builder<T> newParameterBuilder() {
        Map<String, Object> state = new LinkedHashMap<>();
        return proxy(
                Parameter.Value.Builder.class,
                (source, method, args) -> {
                    switch (method.getName()) {
                        case "key":
                            state.put("key", args[0] instanceof String
                                    ? args[0]
                                    : keyNameOf(args[0]));
                            return source;
                        case "optional":
                            state.put("optional", Boolean.TRUE);
                            return source;
                        case "build":
                            return newParameter(state);
                        default:
                            return source;
                    }
                });
    }

    private Parameter.Value<Object> newParameter(Map<String, Object> state) {
        String name = (String) state.get("key");
        boolean optional = Boolean.TRUE.equals(state.get("optional"));
        Parameter.Value<Object> value =
                stub(
                        Parameter.Value.class,
                        results(
                                "key", stub(Parameter.Key.class, results("key", name)),
                                "isOptional", optional));
        parameterKeys.put(value, name);
        return value;
    }

    private static String keyNameOf(Object key) {
        try {
            return (String) key.getClass().getMethod("key").invoke(key);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法读取命令参数键名", error);
        }
    }

    private Task.Builder newTaskBuilder() {
        TaskState state = new TaskState();
        return proxy(
                Task.Builder.class,
                (source, method, args) -> {
                    switch (method.getName()) {
                        case "execute":
                            state.body = (Runnable) args[0];
                            return source;
                        case "plugin":
                            state.plugin = (PluginContainer) args[0];
                            return source;
                        case "delay":
                            state.delayTicks = ticksOf(args[0]);
                            state.delay = durationOf(args[0]);
                            return source;
                        case "interval":
                            state.periodTicks = ticksOf(args[0]);
                            state.interval = durationOf(args[0]);
                            return source;
                        case "build":
                            return buildTask(state);
                        default:
                            return source;
                    }
                });
    }

    private Task buildTask(TaskState state) {
        Task task =
                stub(Task.class, results("plugin", state.plugin, "delay", state.delay, "interval", state.interval));
        taskStates.put(task, state);
        builtTasks.add(task);
        return task;
    }

    private Command.Builder newCommandBuilder() {
        CommandState state = new CommandState();
        return proxy(
                Command.Builder.class,
                (source, method, args) -> {
                    switch (method.getName()) {
                        case "permission":
                            state.permission = (String) args[0];
                            return source;
                        case "addChild":
                            state.children.add((Command.Parameterized) args[0]);
                            return source;
                        case "addParameter":
                            state.parameters.add(args[0]);
                            return source;
                        case "executor":
                            state.executor = (CommandExecutor) args[0];
                            return source;
                        case "build":
                            return newCommand(state);
                        default:
                            return source;
                    }
                });
    }

    private Command.Parameterized newCommand(CommandState state) {
        // 真实 Sponge 把 addChild 的子命令也放进 parameters()（参数化命令的子命令即序列参数），
        // 被测类经 parameters() 读取命令树，故此处保持同一语义。
        List<Object> parameters = new ArrayList<>(state.parameters);
        parameters.addAll(state.children);
        return stub(
                Command.Parameterized.class,
                results(
                        "executor", Optional.ofNullable(state.executor),
                        "parameters", parameters,
                        "subcommands", new ArrayList<>(state.children),
                        "flags", new ArrayList<Object>(),
                        "isTerminal", true));
    }

    private static long ticksOf(Object value) {
        if (value instanceof Ticks) {
            return ((Ticks) value).ticks();
        }
        if (value instanceof Duration) {
            return ((Duration) value).toMillis() / 50L;
        }
        return -1L;
    }

    private static Duration durationOf(Object value) {
        if (value instanceof Ticks) {
            return Duration.ofMillis(((Ticks) value).ticks() * 50L);
        }
        return value instanceof Duration ? (Duration) value : null;
    }

    private static Object readGameField() {
        return accessGameField(null);
    }

    private static void writeGameField(Object game) {
        accessGameField(game);
    }

    private static Object accessGameField(Object game) {
        try {
            Field field = Sponge.class.getDeclaredField("game");
            field.setAccessible(true);
            if (game != null) {
                field.set(null, game);
            }
            return field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法访问 Sponge.game 静态字段", error);
        }
    }

    private static final ResourceKeyFactory RESOURCE_KEY_FACTORY = new ResourceKeyFactory();

    private static final CommandResultFactory COMMAND_RESULT_FACTORY = new CommandResultFactory();

    private static final TicksFactory TICKS_FACTORY = new TicksFactory();

    private static final RegistryTypeFactory REGISTRY_TYPE_FACTORY = new RegistryTypeFactory();

    private static final RegistryKeyFactory REGISTRY_KEY_FACTORY = new RegistryKeyFactory();

    /** 注册表类型工厂替身：只保留根 / 位置键，供 {@code RegistryTypes} 静态初始化通过。 */
    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static final class RegistryTypeFactory implements RegistryType.Factory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> RegistryType<T> create(
                org.spongepowered.api.ResourceKey root, org.spongepowered.api.ResourceKey location) {
            DefaultedRegistryType<T> defaulted =
                    proxy(
                            DefaultedRegistryType.class,
                            (source, method, args) -> {
                                switch (method.getName()) {
                                    case "root":
                                        return root;
                                    case "location":
                                        return location;
                                    case "asDefaultedType":
                                        return source;
                                    default:
                                        return defaultResult(source, method, args);
                                }
                            });
            return proxy(
                    RegistryType.class,
                    (source, method, args) -> {
                        switch (method.getName()) {
                            case "root":
                                return root;
                            case "location":
                                return location;
                            case "asDefaultedType":
                                return defaulted;
                            default:
                                return defaultResult(source, method, args);
                        }
                    });
        }
    }

    /**
     * 注册表键工厂替身：{@code asDefaultedReference} 产出可用的默认化引用。
     *
     * <p>{@code get()} 返回一个"值解析器"替身，使 {@code Parameter.string()} 等走注册表引用构造的
     * 参数在假平台上可用 —— 被测类只需参数具备可寻址的键名，不需真实解析。
     */
    private static final class RegistryKeyFactory implements RegistryKey.Factory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> RegistryKey<T> of(
                RegistryType<T> registry, org.spongepowered.api.ResourceKey location) {
            ResourceKeyedValueParameter<T> parser =
                    proxy(
                            ResourceKeyedValueParameter.class,
                            (source, method, args) -> Optional.empty());
            Supplier<T> value = () -> cast(parser);
            DefaultedRegistryReference<T> reference =
                    proxy(
                            DefaultedRegistryReference.class,
                            (source, method, args) -> {
                                switch (method.getName()) {
                                    case "registry":
                                        return registry;
                                    case "location":
                                        return location;
                                    case "get":
                                        return value.get();
                                    case "find":
                                        return Optional.of(value.get());
                                    case "asReference":
                                    case "asDefaultedReference":
                                        return source;
                                    case "defaultHolder":
                                        return (Supplier<RegistryHolder>) () -> (RegistryHolder) null;
                                    default:
                                        return defaultResult(source, method, args);
                                }
                            });
            return proxy(
                    RegistryKey.class,
                    (source, method, args) -> {
                        switch (method.getName()) {
                            case "registry":
                                return registry;
                            case "location":
                                return location;
                            case "asDefaultedReference":
                                return reference;
                            case "asReference":
                                return reference;
                            default:
                                return defaultResult(source, method, args);
                        }
                    });
        }
    }

    /** tick 工厂替身：{@code ticks()} 原样返回输入值。 */
    private static final class TicksFactory implements Ticks.Factory {
        @Override
        public Ticks of(long ticks) {
            return stub(Ticks.class, results("ticks", ticks));
        }

        @Override
        public Ticks ofWallClockTime(
                org.spongepowered.api.Engine engine, long value, java.time.temporal.TemporalUnit unit) {
            return of(value);
        }

        @Override
        public Ticks ofMinecraftSeconds(org.spongepowered.api.Engine engine, long seconds) {
            return of(seconds * 20L);
        }

        @Override
        public Ticks ofMinecraftHours(org.spongepowered.api.Engine engine, long hours) {
            return of(hours * 72000L);
        }

        @Override
        public Ticks zero() {
            return of(0L);
        }

        @Override
        public Ticks single() {
            return of(1L);
        }

        @Override
        public Ticks minecraftHour() {
            return of(72000L);
        }

        @Override
        public Ticks minecraftDay() {
            return of(1728000L);
        }
    }

    /** 命令结果工厂替身：{@code success()} 恒返回成功结果。 */
    private static final class CommandResultFactory implements CommandResult.Factory {
        @Override
        public CommandResult success() {
            return stub(CommandResult.class, results("isSuccess", true));
        }
    }

    /** 资源键工厂替身：以 {@code namespace:value} 文本产出键。 */
    private static final class ResourceKeyFactory
            implements org.spongepowered.api.ResourceKey.Factory {
        @Override
        public org.spongepowered.api.ResourceKey of(String namespace, String value) {
            return named(namespace + ":" + value);
        }

        @Override
        public org.spongepowered.api.ResourceKey of(PluginContainer plugin, String value) {
            return named("plugin:" + value);
        }

        @Override
        public org.spongepowered.api.ResourceKey resolve(String value) {
            String[] parts = value.split(":", 2);
            return parts.length == 2 ? named(value) : named("minecraft:" + value);
        }

        private static org.spongepowered.api.ResourceKey named(String text) {
            return stub(
                    org.spongepowered.api.ResourceKey.class,
                    results("formatted", text, "asString", text));
        }
    }

    /** 单个任务构建器的可变状态。 */
    private static final class TaskState {
        private Runnable body;
        private PluginContainer plugin;
        private Duration delay;
        private Duration interval;
        private long delayTicks = -1L;
        private long periodTicks = -1L;
    }

    /** 单个命令构建器的可变状态。 */
    private static final class CommandState {
        private String permission;
        private final List<Command.Parameterized> children = new ArrayList<>();
        private final List<Object> parameters = new ArrayList<>();
        private CommandExecutor executor;

        /** 构建期声明的权限节点；供测试断言产品命令确实挂上了权限门槛。 */
        String permission() {
            return permission;
        }
    }
}
