package top.wcpe.mc.mpmt.platform.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Server;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * {@link MpmtSpongePlugin} 的三段式生命周期（构造期 → 通道注册期 → 引擎启动期 → 停服）与只读接缝。
 *
 * <p>经假 SpongeAPI 静态 Holder 让整条装配链（{@code PlatformProvider.boot} → 版本探测 → 通道注册 →
 * 能力端口装配 → 引擎启动后启用运行时并接线）在纯 JVM 下真实跑通。
 *
 * <p>三段式的由来：心跳定时器会触达 {@code Sponge.server()}，而 RC1365 在 RegisterChannel 阶段专用服
 * 尚未就绪，故 {@code enable()} 必须推迟到 {@link StartedEngineEvent}。
 */
class SpongePluginLifecycleTest {

    private SpongeTestRuntime sponge;
    private MpmtSpongePlugin plugin;

    @AfterEach
    void 清理platform绑定() {
        // PlatformProvider 是进程级单例：testCase结束必须释放，否则同 JVM 后续 boot 必失败
        if (PlatformProvider.isBooted()) {
            PlatformProvider.deactivate();
        }
        if (sponge != null) {
            sponge.close();
            sponge = null;
        }
    }

    @Test
    @DisplayName("构造期：创建运行时并保持 NEW，data目录接缝立即可读")
    void 构造期创建运行时() throws Exception {
        装配插件();

        plugin.onConstruct(constructEvent());

        assertEquals(MpmtRuntime.Phase.NEW, runtimeOf(plugin).phase(), "构造期不应启用运行时");
        assertEquals(Path.of("config", "mpmt"), plugin.dataDirectory());
        assertFalse(PlatformProvider.isBooted(), "构造期不应留下platform绑定");
    }

    @Test
    @DisplayName("通道注册期：探测版本、注册 mpmt:main 通道、装配端口，但不启用运行时")
    void 通道注册期只装配不启用() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        List<String> registeredChannel = new ArrayList<>();
        List<Class<?>> registeredType = new ArrayList<>();

        plugin.onRegisterChannels(channelEvent(registeredChannel, registeredType));

        MpmtRuntime runtime = runtimeOf(plugin);
        assertEquals(MpmtRuntime.Phase.NEW, runtime.phase(), "心跳依赖service端，通道注册期不得启用");
        assertEquals(List.of("mpmt:main"), registeredChannel, "应注册锚点通道键");
        assertEquals(List.of(RawDataChannel.class), registeredType);
        assertNotNull(runtime.ports().get(SchedulerPort.class), "scheduler端口必须已装配");
        assertTrue(PlatformProvider.isBooted(), "装配成功后应留下进程级platform绑定");
        assertEquals("sponge", PlatformProvider.get().platformId());
        assertNotNull(plugin.serverNetworkFeature(), "网络特性对象应已构造");
        // 通道注册期已发生一次事件注册（能力装配的player连接桥），但service端连接监听器要到引擎启动后才注册
        assertEquals(1, sponge.registeredListeners.size(), "此时只应有能力装配的player连接桥");
        assertTrue(
                sponge.registeredListeners.get(0)
                        instanceof top.wcpe.mc.mpmt.platform.sponge.capability.SpongeCapabilityBootstrap
                                .PlayerConnectionBridge,
                "引擎启动前不应注册service端连接监听器");
    }

    @Test
    @DisplayName("引擎启动期：启用运行时、注册连接监听器、接线封禁service并异步加载快照")
    void 引擎启动期启用并接线() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));

        plugin.onStarted(startedEvent());

        MpmtRuntime runtime = runtimeOf(plugin);
        assertEquals(MpmtRuntime.Phase.ENABLED, runtime.phase(), "引擎启动后必须已启用");
        assertEquals(2, sponge.registeredListeners.size(), "应追加注册player进退服监听器");
        assertTrue(
                sponge.registeredListeners.get(1) instanceof SpongeServerConnectionListener,
                "第二项应为service端连接监听器");
        assertTrue(sponge.asyncTasks.size() >= 1, "封禁快照加载应提交到异步scheduler器");

        sponge.runAsyncTasks();
        assertEquals(BanService.State.READY, banServiceOf(plugin).state(), "异步加载后应为就绪");
    }

    @Test
    @DisplayName("引擎启动期：repeat 触发已启用运行时也不抛异常")
    void 引擎启动期幂等() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());

        plugin.onStarted(startedEvent());

        assertEquals(MpmtRuntime.Phase.ENABLED, runtimeOf(plugin).phase());
    }

    @Test
    @DisplayName("引擎启动期：未经通道装配就启动时只记录错误、不启用运行时")
    void 引擎启动期缺少装配结果时跳过() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());

        plugin.onStarted(startedEvent());

        assertEquals(MpmtRuntime.Phase.NEW, runtimeOf(plugin).phase(), "缺装配结果不得启用");
        assertTrue(sponge.registeredListeners.isEmpty(), "不应注册任何监听器");
    }

    @Test
    @DisplayName("引擎启动期：未构造运行时直接启动也不抛异常")
    void 引擎启动期未构造运行时() throws Exception {
        装配插件();

        plugin.onStarted(startedEvent());

        assertFalse(PlatformProvider.isBooted());
    }

    @Test
    @DisplayName("command注册期：以 mpmt 为名注册args化机器码command")
    void command注册期注册机器码command() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());
        List<String> commandName = new ArrayList<>();
        List<Command.Parameterized> command = new ArrayList<>();

        plugin.onRegisterCommands(commandEvent(commandName, command));

        assertEquals(List.of("mpmt"), commandName);
        assertEquals(1, command.size());
        assertEquals(1, command.get(0).parameters().size(), "应挂 machinecode 子command");
    }

    @Test
    @DisplayName("查询接缝：未装配时网络特性失败快，另有player连接桥已注册随装配")
    void 查询接缝在未装配时失败快() throws Exception {
        装配插件();

        IllegalStateException featureError =
                assertThrows(IllegalStateException.class, plugin::serverNetworkFeature);
        assertTrue(featureError.getMessage().contains("尚未启用"));
        assertFalse(PlatformProvider.isBooted(), "未装配不应有platform绑定");
    }

    @Test
    @DisplayName("查询接缝：装配后无会话时握手状态返回空")
    void 握手状态无会话返回空() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());

        assertNull(plugin.handshakeState(UUID.randomUUID()), "尚无会话时应返回空");
    }

    @Test
    @DisplayName("查询接缝：按player对象取物理句柄且同一player复用同一句柄，空player即拒绝")
    void 连接句柄按player身份复用() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());
        ServerPlayer player = sponge.addPlayer(UUID.randomUUID(), "甲");

        assertNotNull(plugin.connectionFor(player));
        assertEquals(plugin.connectionFor(player), plugin.connectionFor(player));
        assertThrows(NullPointerException.class, () -> plugin.connectionFor(null));
    }

    @Test
    @DisplayName("查询接缝：registry已追踪的连接可按player查得握手状态")
    void 握手状态按已登记连接查得() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = sponge.addPlayer(playerId, "乙");

        plugin.connectionFor(player);
        plugin.serverNetworkFeature().onConnected(plugin.connectionFor(player));

        assertNotNull(plugin.handshakeState(playerId), "已登记且已连接的会话应有握手状态");
    }

    @Test
    @DisplayName("查询接缝：下发 ACTIONBAR HUD 拒绝空文本")
    void 下发HUD拒绝空文本() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());
        ServerPlayer player = sponge.addPlayer(UUID.randomUUID(), "丙");

        assertThrows(NullPointerException.class, () -> plugin.sendActionBarHud(player, null));
    }

    @Test
    @DisplayName("查询接缝：下发 ACTIONBAR HUD 向当前连接完成真实派发")
    void 下发HUD对当前连接成功() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = sponge.addPlayer(playerId, "丁");
        plugin.connectionFor(player);

        plugin.sendActionBarHud(player, "在线心跳");

        assertTrue(sponge.dispatchedPayloads.size() >= 1, "应经物理通道向该player派发负载");
    }

    @Test
    @DisplayName("停服：停用运行时、释放进程级绑定并清空全部装配产物")
    void 停服释放运行时与platform绑定() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());

        plugin.onStopping(stoppingEvent());

        assertEquals(MpmtRuntime.Phase.DISABLED, runtimeOf(plugin).phase());
        assertFalse(PlatformProvider.isBooted(), "停服必须释放进程级platform绑定");
        assertThrows(IllegalStateException.class, plugin::serverNetworkFeature, "特性引用应已清空");
        assertThrows(
                IllegalStateException.class,
                () -> plugin.handshakeState(UUID.randomUUID()),
                "连接registry引用应已清空");
    }

    @Test
    @DisplayName("停服：未启用过运行时也不抛异常（幂等释放）")
    void 停服幂等() throws Exception {
        装配插件();

        plugin.onStopping(stoppingEvent());

        assertFalse(PlatformProvider.isBooted());
    }

    @Test
    @DisplayName("停服后同 JVM 可重新装配，不被进程级绑定误拦")
    void 停服后可重新装配() throws Exception {
        装配插件();
        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());
        plugin.onStopping(stoppingEvent());

        plugin.onConstruct(constructEvent());
        plugin.onRegisterChannels(channelEvent(new ArrayList<>(), new ArrayList<>()));
        plugin.onStarted(startedEvent());

        assertEquals(MpmtRuntime.Phase.ENABLED, runtimeOf(plugin).phase());
    }

    private void 装配插件() throws Exception {
        sponge = SpongeTestRuntime.install();
        Constructor<MpmtSpongePlugin> constructor =
                MpmtSpongePlugin.class.getDeclaredConstructor(
                        Logger.class, PluginContainer.class, Path.class);
        constructor.setAccessible(true);
        plugin =
                constructor.newInstance(
                        LogManager.getLogger("mpmt"),
                        SpongeTestRuntime.pluginContainer(),
                        Path.of("config", "mpmt"));
    }

    private static MpmtRuntime runtimeOf(MpmtSpongePlugin target) throws Exception {
        return (MpmtRuntime) field(target, "runtime");
    }

    private static BanService banServiceOf(MpmtSpongePlugin target) throws Exception {
        return (BanService) field(target, "banService");
    }

    private static Object field(MpmtSpongePlugin target, String name) throws Exception {
        Field field = MpmtSpongePlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ConstructPluginEvent constructEvent() {
        return SpongeTestRuntime.stub(ConstructPluginEvent.class, SpongeTestRuntime.results());
    }

    private static StartedEngineEvent<Server> startedEvent() {
        return SpongeTestRuntime.stub(StartedEngineEvent.class, SpongeTestRuntime.results());
    }

    private static StoppingEngineEvent<Server> stoppingEvent() {
        return SpongeTestRuntime.stub(StoppingEngineEvent.class, SpongeTestRuntime.results());
    }

    /** 造通道注册事件：记录注册的键与通道类型，并返回可用的原始data通道替身。 */
    private RegisterChannelEvent channelEvent(
            List<String> registeredChannel, List<Class<?>> registeredType) {
        return SpongeTestRuntime.proxy(
                RegisterChannelEvent.class,
                (source, method, args) -> {
                    if (!method.getName().equals("register")) {
                        return SpongeTestRuntime.defaultResult(source, method, args);
                    }
                    registeredChannel.add(((ResourceKey) args[0]).formatted());
                    registeredType.add((Class<?>) args[1]);
                    return sponge.rawDataChannel();
                });
    }

    private static RegisterCommandEvent<Command.Parameterized> commandEvent(
            List<String> commandName, List<Command.Parameterized> command) {
        return SpongeTestRuntime.proxy(
                RegisterCommandEvent.class,
                (source, method, args) -> {
                    if (method.getName().equals("register")) {
                        commandName.add((String) args[2]);
                        command.add((Command.Parameterized) args[1]);
                    }
                    return SpongeTestRuntime.defaultResult(source, method, args);
                });
    }
}
