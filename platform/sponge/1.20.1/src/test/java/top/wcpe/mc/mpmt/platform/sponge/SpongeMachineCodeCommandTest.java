package top.wcpe.mc.mpmt.platform.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandExecutor;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;

/**
 * {@link SpongeMachineCodeCommand} 与 {@link SpongeMachineCodeCommand.Responder} 的分发与边界。
 *
 * <p>经假 SpongeAPI 静态 Holder 让真command树 {@code Command.builder()} 可构建；用<b>真</b>
 * {@link BanService} 驱动 ban / unban / list 三条执行路径，只把其依赖的persistence / scheduler / 连接控制端口
 * 换成可控替身，从而真实覆盖"已就绪 / 未就绪 / persistence成功 / persistence失败 / player归属 / 控制台归属 /
 * player离线 / 缺失args"。
 */
class SpongeMachineCodeCommandTest {

    @Test
    @DisplayName("command树：顶层挂 machinecode，其下挂 ban / unban / list 三条带执行器的子command")
    void command树结构() throws Exception {
        try (TestCase scenario = new TestCase()) {
            Command.Parameterized root = scenario.rootCommand();

            assertEquals(1, root.parameters().size(), "顶层应挂 machinecode 子command");
            Command.Parameterized machineCode = (Command.Parameterized) root.parameters().get(0);
            assertEquals(3, machineCode.parameters().size(), "machinecode 应挂三条子command");
            for (Object child : machineCode.parameters()) {
                assertNotNull(
                        ((Command.Parameterized) child).executor().orElse(null),
                        "每条子command都必须声明执行器");
            }
        }
    }

    @Test
    @DisplayName("ban：声明必填 code 与可选 reason 两个args，键名稳定")
    void ban子command声明两个args() throws Exception {
        try (TestCase scenario = new TestCase()) {
            Command.Parameterized ban = scenario.bancommand();

            assertEquals(2, ban.parameters().size(), "ban 应声明 code 与可选 reason");
            assertEquals("code", keyOf(ban.parameters().get(0)));
            assertEquals("reason", keyOf(ban.parameters().get(1)));
            assertTrue(((Parameter) ban.parameters().get(1)).isOptional(), "reason 必须可选");
        }
    }

    @Test
    @DisplayName("权限常量：统一声明 mpmt.machinecode.manage")
    void 权限常量稳定() throws Exception {
        Field field = SpongeMachineCodeCommand.class.getDeclaredField("PERMISSION");
        field.setAccessible(true);

        assertEquals("mpmt.machinecode.manage", field.get(null));
    }

    @Test
    @DisplayName("ban：persistence成功后写入registry，并在全局scheduler器回复成功文案")
    void ban成功回复() throws Exception {
        try (TestCase scenario = new TestCase()) {
            Object result = scenario.执行(scenario.bancommand(), scenario.控制台上下文(Map.of("code", "机器码甲")));

            assertEquals(Boolean.TRUE, invoke(result, "isSuccess"), "command应返回成功结果");
            scenario.跑全部();

            assertEquals(1, scenario.registry().list().size(), "封禁应提交到内存registry");
            assertEquals("机器码甲", scenario.registry().list().get(0).getCode().getValue());
            assertEquals("管理员封禁", scenario.registry().list().get(0).getReason(), "缺省理由应为管理员封禁");
            assertEquals("machine-code-bans", scenario.persistence().最后写入的命名空间());
            assertEquals(
                    "v1\n" + base64("机器码甲") + "\t" + base64("管理员封禁"),
                    scenario.persistence().最后写入的值(),
                    "快照应为版本行 + Base64 编码的机器码与理由");

            assertEquals(1, scenario.message().size());
            assertTrue(scenario.message().get(0).toString().contains("已封禁机器码：机器码甲"));
        }
    }

    @Test
    @DisplayName("ban：显式理由覆盖缺省值")
    void ban显式理由() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.执行(
                    scenario.bancommand(),
                    scenario.控制台上下文(Map.of("code", "机器码乙", "reason", "刷屏作弊")));
            scenario.跑全部();

            assertEquals("刷屏作弊", scenario.registry().list().get(0).getReason());
        }
    }

    @Test
    @DisplayName("ban：persistence失败时回复失败文案并带出根因message")
    void ban失败回复() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.persistence().设定writeFailure(new CompletionException(new IllegalStateException("磁盘只读")));

            scenario.执行(scenario.bancommand(), scenario.控制台上下文(Map.of("code", "机器码丙")));
            scenario.跑全部();

            assertEquals(1, scenario.message().size());
            assertTrue(scenario.message().get(0).toString().contains("封禁失败"));
            assertTrue(scenario.message().get(0).toString().contains("磁盘只读"));
            assertTrue(scenario.registry().list().isEmpty(), "persistence失败不得提交内存状态");
        }
    }

    @Test
    @DisplayName("ban：CompletionException 包裹时取根因message而非包装类名")
    void ban失败取根因message() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.persistence()
                    .设定writeFailure(
                            new CompletionException(new IllegalStateException("persistence不可写")));

            scenario.执行(scenario.bancommand(), scenario.控制台上下文(Map.of("code", "机器码丁")));
            scenario.跑全部();

            String text = scenario.message().get(0).toString();
            assertTrue(text.contains("persistence不可写"));
            assertTrue(!text.contains("CompletionException"), "不应把包装异常名当根因");
        }
    }

    @Test
    @DisplayName("ban：根因无message时退化为根因类名")
    void ban失败退化为类名() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.persistence().设定writeFailure(new CompletionException(new IllegalStateException()));

            scenario.执行(scenario.bancommand(), scenario.控制台上下文(Map.of("code", "机器码戊")));
            scenario.跑全部();

            assertTrue(scenario.message().get(0).toString().contains("IllegalStateException"));
        }
    }

    @Test
    @DisplayName("ban：service未就绪时回复失败并带出未就绪状态")
    void ban未就绪时回复失败() throws Exception {
        try (TestCase scenario = new TestCase(false)) {
            scenario.执行(scenario.bancommand(), scenario.控制台上下文(Map.of("code", "机器码未就绪")));
            scenario.跑全部();

            assertTrue(scenario.message().get(0).toString().contains("封禁失败"));
            assertTrue(scenario.message().get(0).toString().contains("尚未就绪"));
        }
    }

    @Test
    @DisplayName("unban：persistence成功后从registry移除，并回复解封成功文案")
    void unban成功回复() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.registry().replaceAll(List.of(new BanEntry(new MachineCode("机器码己"), "旧理由")));

            scenario.执行(scenario.unbancommand(), scenario.控制台上下文(Map.of("code", "机器码己")));
            scenario.跑全部();

            assertTrue(scenario.registry().list().isEmpty(), "解封应从registry移除");
            assertTrue(scenario.message().get(0).toString().contains("已解封机器码：机器码己"));
        }
    }

    @Test
    @DisplayName("unban：persistence失败时回复解封失败并带出根因")
    void unban失败回复() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.registry().replaceAll(List.of(new BanEntry(new MachineCode("机器码庚"), "旧理由")));
            scenario.persistence().设定writeFailure(new CompletionException(new IllegalStateException("读盘失败")));

            scenario.执行(scenario.unbancommand(), scenario.控制台上下文(Map.of("code", "机器码庚")));
            scenario.跑全部();

            assertTrue(scenario.message().get(0).toString().contains("解封失败"));
            assertTrue(scenario.message().get(0).toString().contains("读盘失败"));
            assertEquals(1, scenario.registry().list().size(), "persistence失败不得移除内存状态");
        }
    }

    @Test
    @DisplayName("list：service未就绪时直接回复当前状态，不读列表")
    void list未就绪回复状态() throws Exception {
        try (TestCase scenario = new TestCase(false)) {
            scenario.执行(scenario.listcommand(), scenario.控制台上下文(Collections.emptyMap()));
            scenario.跑全部();

            assertEquals(1, scenario.message().size());
            assertTrue(scenario.message().get(0).toString().contains("封禁列表读取失败"));
            assertTrue(scenario.message().get(0).toString().contains("NEW"));
        }
    }

    @Test
    @DisplayName("list：persistence加载失败后状态为 FAILED，list 回复该状态")
    void list加载失败后回复失败状态() throws Exception {
        try (TestCase scenario = new TestCase(false)) {
            scenario.persistence().设定readFailure(new IllegalStateException("快照损坏"));
            scenario.初始化();
            scenario.跑全部();

            assertEquals(BanService.State.FAILED, scenario.service().state());
            scenario.执行(scenario.listcommand(), scenario.控制台上下文(Collections.emptyMap()));
            scenario.跑全部();

            assertTrue(scenario.message().get(0).toString().contains("FAILED"));
        }
    }

    @Test
    @DisplayName("list：就绪但无封禁时回复空列表文案")
    void list空列表回复() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.执行(scenario.listcommand(), scenario.控制台上下文(Collections.emptyMap()));
            scenario.跑全部();

            assertTrue(scenario.message().get(0).toString().contains("当前没有机器码封禁"));
        }
    }

    @Test
    @DisplayName("list：有封禁时逐条列出机器码与理由")
    void list有内容时逐条列出() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.registry()
                    .replaceAll(
                            List.of(
                                    new BanEntry(new MachineCode("机器码一"), "理由一"),
                                    new BanEntry(new MachineCode("机器码二"), "理由二")));

            scenario.执行(scenario.listcommand(), scenario.控制台上下文(Collections.emptyMap()));
            scenario.跑全部();

            String text = scenario.message().get(0).toString();
            assertTrue(text.contains("机器码封禁列表（2）"), text);
            assertTrue(text.contains("机器码一"));
            assertTrue(text.contains("理由一"));
            assertTrue(text.contains("机器码二"));
            assertTrue(text.contains("理由二"));
        }
    }

    @Test
    @DisplayName("响应者：player归属走实体scheduler并按 UUID 重查在线player投递")
    void player归属走实体scheduler() throws Exception {
        try (TestCase scenario = new TestCase()) {
            UUID playerId = UUID.randomUUID();
            ServerPlayer player = scenario.platform().addPlayer(playerId, "甲");

            scenario.执行(scenario.bancommand(), scenario.player上下文(Map.of("code", "机器码辛"), player));
            scenario.跑全部();

            assertEquals(1, scenario.scheduler().entityAffinity.size(), "player归属应走 runForEntity");
            assertEquals(new EntityRef(playerId), scenario.scheduler().entityAffinity.get(0));
            assertTrue(scenario.scheduler().globalTasks.isEmpty(), "player归属不应走全局scheduler");

            assertEquals(1, scenario.platform().playerMessages.size(), "message应发给该在线player");
            assertTrue(
                    scenario.platform().playerMessages.get(playerId).get(0).contains("已封禁机器码：机器码辛"));
        }
    }

    @Test
    @DisplayName("响应者：player已离线时静默丢弃，不抛异常")
    void player离线时静默丢弃() throws Exception {
        try (TestCase scenario = new TestCase()) {
            UUID playerId = UUID.randomUUID();
            ServerPlayer player = scenario.platform().addPlayer(playerId, "乙");

            scenario.执行(scenario.bancommand(), scenario.player上下文(Map.of("code", "机器码壬"), player));
            scenario.platform().clearPlayers();
            scenario.跑全部();

            assertTrue(scenario.platform().playerMessages.isEmpty(), "已离线player不应收到message");
        }
    }

    @Test
    @DisplayName("响应者：控制台归属走全局scheduler并经command上下文发送message")
    void 控制台归属走全局scheduler() throws Exception {
        try (TestCase scenario = new TestCase()) {
            scenario.执行(scenario.bancommand(), scenario.控制台上下文(Map.of("code", "机器码癸")));
            scenario.跑异步();

            assertEquals(1, scenario.scheduler().globalTasks.size(), "控制台归属应走 runGlobal");
            assertTrue(scenario.scheduler().entityAffinity.isEmpty(), "控制台归属不应走实体scheduler");

            scenario.跑同步();
            assertEquals(1, scenario.message().size(), "控制台应经command上下文收到回复");
        }
    }

    @Test
    @DisplayName("构造：缺失封禁service或scheduler器提供者即拒绝")
    void 构造拒绝空提供者() {
        // null 提供者：null 检查先于command树构建，故直接抛 NPE
        assertThrows(NullPointerException.class, () -> 工厂(null, (Supplier<SchedulerPort>) () -> null));
        assertThrows(NullPointerException.class, () -> 工厂(封禁service提供者(null), null));
    }

    @Test
    @DisplayName("构造：service提供者返回空时执行失败快，不静默吞掉")
    void service未装配时失败快() throws Exception {
        try (TestCase scenario = new TestCase()) {
            // 提供者返回空：复用真command树结构，但执行器替换为"service为空"的那一份
            Command.Parameterized emptyService =
                    工厂((Supplier<BanService>) () -> null, scenario.scheduler提供者());
            Command.Parameterized command =
                    childCommand(emptyService, 0);

            assertThrows(
                    IllegalStateException.class,
                    () -> scenario.执行(command, scenario.控制台上下文(Map.of("code", "机器码甲"))));
        }
    }

    @Test
    @DisplayName("执行：缺失机器码args时 requireOne 抛出，不静默吞掉")
    void 缺失args时抛出() throws Exception {
        try (TestCase scenario = new TestCase()) {
            assertThrows(
                    NoSuchElementException.class,
                    () -> scenario.执行(scenario.bancommand(), scenario.控制台上下文(Collections.emptyMap())));
        }
    }

    /** 一条 TestCase 的最小装配：假platform运行时 + 可控端口 + 真封禁service + 真command树。 */
    private static final class TestCase implements AutoCloseable {
        private final SpongeTestRuntime platform = SpongeTestRuntime.install();
        private final RecordingSchedulerPort scheduler = new RecordingSchedulerPort();
        private final ControllablePersistencePort persistence = new ControllablePersistencePort();
        private final BanRegistry registry = new BanRegistry();
        private final BanService service;
        private final Command.Parameterized rootCommand;
        private final List<Component> message = new ArrayList<>();

        TestCase() {
            this(true);
        }

        /** @param initializeImmediately 是否立刻驱动异步初始化到 READY */
        TestCase(boolean initializeImmediately) {
            service =
                    new BanService(
                            registry, new SessionRegistry(), persistence, scheduler, new FakeConnectionControlPort());
            rootCommand = 工厂(() -> service, () -> scheduler);
            if (initializeImmediately) {
                初始化();
                跑全部();
                message.clear();
            }
        }

        void 初始化() {
            service.initialize();
        }

        Command.Parameterized rootCommand() {
            return rootCommand;
        }

        Command.Parameterized bancommand() {
            return childCommand(rootCommand, 0);
        }

        Command.Parameterized unbancommand() {
            return childCommand(rootCommand, 1);
        }

        Command.Parameterized listcommand() {
            return childCommand(rootCommand, 2);
        }

        Supplier<SchedulerPort> scheduler提供者() {
            return () -> scheduler;
        }

        CommandContext 控制台上下文(Map<String, String> args) {
            return platform.context(SpongeTestRuntime.consoleCause(), args, message);
        }

        CommandContext player上下文(Map<String, String> args, ServerPlayer player) {
            return platform.context(SpongeTestRuntime.playerCause(player), args, message);
        }

        Object 执行(Command.Parameterized command, CommandContext context) throws Exception {
            CommandExecutor executor =
                    command.executor().orElseThrow(() -> new AssertionError("缺少执行器"));
            return executor.execute(context);
        }

        /** 先跑异步（persistence / BanService worker），再跑同步（Responder 投递）。 */
        void 跑全部() {
            跑异步();
            跑同步();
        }

        void 跑异步() {
            scheduler.跑异步();
        }

        void 跑同步() {
            scheduler.跑同步();
        }

        List<Component> message() {
            return message;
        }

        SpongeTestRuntime platform() {
            return platform;
        }

        BanRegistry registry() {
            return registry;
        }

        BanService service() {
            return service;
        }

        ControllablePersistencePort persistence() {
            return persistence;
        }

        RecordingSchedulerPort scheduler() {
            return scheduler;
        }

        @Override
        public void close() {
            platform.close();
        }
    }

    /** ControllablePersistencePort：正常读写内存，必要时按指令抛异常。 */
    private static final class ControllablePersistencePort implements PersistencePort {
        private final Map<String, String> data = new LinkedHashMap<>();
        private RuntimeException readFailure;
        private RuntimeException writeFailure;
        private String lastNamespace;
        private String lastValue;

        void 设定readFailure(RuntimeException error) {
            this.readFailure = error;
        }

        void 设定writeFailure(RuntimeException error) {
            this.writeFailure = error;
        }

        String 最后写入的命名空间() {
            return lastNamespace;
        }

        String 最后写入的值() {
            return lastValue;
        }

        @Override
        public Optional<String> read(String namespace, String key) {
            if (readFailure != null) {
                throw readFailure;
            }
            return Optional.ofNullable(data.get(namespace + '/' + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            if (writeFailure != null) {
                throw writeFailure;
            }
            lastNamespace = namespace;
            lastValue = value;
            data.put(namespace + '/' + key, value);
        }
    }

    /** 仅满足父类构造的连接控制端口替身。 */
    private static final class FakeConnectionControlPort implements ConnectionControlPort {
        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            return new EntityRef(UUID.randomUUID());
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            // 无会话表时不会被调用
        }
    }

    /** 记录受理任务与归属的scheduler端口替身，按同步 / 异步分槽以便测试显式驱动。 */
    private static final class RecordingSchedulerPort implements SchedulerPort {
        private final List<Runnable> globalTasks = new ArrayList<>();
        private final List<Runnable> entityTasks = new ArrayList<>();
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final List<EntityRef> entityAffinity = new ArrayList<>();

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            entityAffinity.add(entity);
            entityTasks.add(task);
        }

        @Override
        public void runForLocation(WorldRef world, int x, int z, Runnable task) {
            entityTasks.add(task);
        }

        @Override
        public void runGlobal(Runnable task) {
            globalTasks.add(task);
        }

        @Override
        public void runAsync(Runnable task) {
            asyncTasks.add(task);
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            return () -> {};
        }

        void 跑同步() {
            按序执行(globalTasks);
            按序执行(entityTasks);
        }

        void 跑异步() {
            按序执行(asyncTasks);
        }

        private static void 按序执行(List<Runnable> queue) {
            List<Runnable> pending = new ArrayList<>(queue);
            queue.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static Supplier<BanService> 封禁service提供者(BanService service) {
        return () -> service;
    }

    /** 快照编码用的 Base64（UTF-8）。 */
    private static String base64(String text) {
        return java.util.Base64.getEncoder()
                .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 反射调用包私有工厂，绕过真实 Sponge service端注册；被调用方抛出的运期异常原样透出。 */
    private static Command.Parameterized 工厂(
            Supplier<BanService> service, Supplier<SchedulerPort> scheduler) {
        try {
            Method create =
                    SpongeMachineCodeCommand.class.getDeclaredMethod(
                            "create", Supplier.class, Supplier.class);
            create.setAccessible(true);
            return (Command.Parameterized) create.invoke(null, service, scheduler);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("command树构建失败", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("command树构建失败", error);
        }
    }

    private static Command.Parameterized childCommand(Command.Parameterized root, int index) {
        Command.Parameterized machineCode = (Command.Parameterized) root.parameters().get(0);
        return (Command.Parameterized) machineCode.parameters().get(index);
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static String keyOf(Object parameter) throws Exception {
        Object key = parameter.getClass().getMethod("key").invoke(parameter);
        return (String) key.getClass().getMethod("key").invoke(key);
    }
}
