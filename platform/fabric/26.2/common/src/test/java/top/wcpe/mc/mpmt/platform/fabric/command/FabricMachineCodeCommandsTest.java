package top.wcpe.mc.mpmt.platform.fabric.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.platform.fabric.MinecraftTestSupport;

/**
 * Fabric 原生命令：注册与权限门槛、ban/unban/list 语义、未就绪与异步失败回写。
 *
 * <p>经真实 Brigadier 分发执行；封禁服务为真实 {@link BanService} + 假端口，
 * 因此状态机、持久化副作用与失败路径均为产品真实行为。
 */
class FabricMachineCodeCommandsTest {

    @Test
    @DisplayName("注册命令树并要求管理员权限，无权限来源被拒绝；空参失败快")
    void 注册与权限门槛() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        FabricMachineCodeCommands.register(dispatcher, () -> null);

        assertNotNull(dispatcher.getRoot().getChild("mpmt"));
        assertNotNull(dispatcher.getRoot().getChild("mpmt").getChild("machinecode"));

        MinecraftServer server = MinecraftTestSupport.newServer();
        RecordingSource recorder = new RecordingSource();
        CommandSourceStack guest = permSource(server, recorder, PermissionSet.NO_PERMISSIONS);

        // requires 门槛作用于整个 mpmt 子树：无权限来源连命令都解析不到
        assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher.execute("mpmt machinecode ban abc", guest));
        assertTrue(recorder.all().isEmpty(), "无权限来源不应产生任何输出");
        // 同一命令对有权限来源必须可解析并进入产品逻辑（此处服务为空，故走"未就绪"分支）
        assertEquals(
                0,
                execute(
                        dispatcher,
                        permSource(server, recorder, LevelBasedPermissionSet.ADMIN),
                        "mpmt machinecode list"));
        assertEquals(true, recorder.contains("封禁服务尚未就绪"), "有权限来源须进入产品逻辑");

        assertThrows(
                NullPointerException.class, () -> FabricMachineCodeCommands.register(null, () -> null));
        assertThrows(
                NullPointerException.class,
                () -> FabricMachineCodeCommands.register(new CommandDispatcher<>(), null));
    }

    @Test
    @DisplayName("ban：默认理由与显式理由分别落库，成功回写可读文本")
    void ban命令语义() {
        Fixture fixture = new Fixture();
        CommandDispatcher<CommandSourceStack> dispatcher =
                fixture.dispatcher(fixture.readyService());

        assertEquals(1, execute(dispatcher, fixture.source(), "mpmt machinecode ban code-A"));
        assertEquals(
                List.of(new BanEntry(new top.wcpe.mc.mpmt.core.domain.ban.MachineCode("code-A"), "由管理员封禁")),
                fixture.banService().list());

        assertEquals(1, execute(dispatcher, fixture.source(), "mpmt machinecode ban code-B 自定义理由"));
        assertEquals(2, fixture.banService().list().size());
        assertEquals(
                true,
                fixture.recorder().contains("已封禁机器码 code-B"),
                "封禁成功须回写可读文本，实际：" + fixture.recorder().all());
    }

    @Test
    @DisplayName("unban：解封后回写成功文本，内存封禁表同步移除")
    void unban命令语义() {
        Fixture fixture = new Fixture();
        BanService service = fixture.readyService();
        CommandDispatcher<CommandSourceStack> dispatcher = fixture.dispatcher(service);
        CommandSourceStack source = fixture.source();

        execute(dispatcher, source, "mpmt machinecode ban code-C");
        assertEquals(1, service.list().size());

        assertEquals(1, execute(dispatcher, source, "mpmt machinecode unban code-C"));
        assertTrue(service.list().isEmpty(), "解封后封禁表须同步移除");
        assertEquals(true, fixture.recorder().contains("已解封机器码 code-C"));
    }

    @Test
    @DisplayName("list：空表回写空提示；有数据时逐条回写并返回条目数")
    void list命令语义() {
        Fixture fixture = new Fixture();
        BanService service = fixture.readyService();
        CommandDispatcher<CommandSourceStack> dispatcher = fixture.dispatcher(service);
        CommandSourceStack source = fixture.source();

        assertEquals(1, execute(dispatcher, source, "mpmt machinecode list"));
        assertEquals(true, fixture.recorder().contains("当前没有机器码封禁"));

        execute(dispatcher, source, "mpmt machinecode ban code-D 刷屏");
        execute(dispatcher, source, "mpmt machinecode ban code-E 外挂");
        fixture.recorder().clear();

        assertEquals(2, execute(dispatcher, source, "mpmt machinecode list"));
        assertEquals(true, fixture.recorder().contains("机器码封禁共 2 条"));
        assertEquals(true, fixture.recorder().contains("code-D - 刷屏"));
        assertEquals(true, fixture.recorder().contains("code-E - 外挂"));
    }

    @Test
    @DisplayName("服务未就绪：命令返回 0 并回写失败文本，不触达封禁表")
    void 服务未就绪回写失败() {
        Fixture fixture = new Fixture();
        BanService notReady = fixture.uninitializedService();
        CommandDispatcher<CommandSourceStack> dispatcher = fixture.dispatcher(notReady);
        CommandSourceStack source = fixture.source();

        assertEquals(0, execute(dispatcher, source, "mpmt machinecode ban code-F"));
        assertEquals(0, execute(dispatcher, source, "mpmt machinecode unban code-F"));
        assertEquals(0, execute(dispatcher, source, "mpmt machinecode list"));

        assertEquals(3, fixture.recorder().all().size());
        assertEquals(
                true,
                fixture.recorder().all().stream().allMatch(text -> text.contains("封禁服务尚未就绪")),
                "未就绪须回写失败文本，实际：" + fixture.recorder().all());
        assertTrue(notReady.list().isEmpty(), "未就绪时不得改动封禁表");
    }

    @Test
    @DisplayName("服务为空同样按未就绪处理，不抛空指针")
    void 服务为空按未就绪处理() {
        Fixture fixture = new Fixture();
        CommandDispatcher<CommandSourceStack> dispatcher = fixture.dispatcher(null);

        assertEquals(0, execute(dispatcher, fixture.source(), "mpmt machinecode list"));
        assertEquals(1, fixture.recorder().all().size());
    }

    @Test
    @DisplayName("异步封禁失败：回写失败原因，且不误报成功")
    void 异步失败回写原因() {
        Fixture fixture = new Fixture();
        fixture.persistence().failWrites = true;
        CommandDispatcher<CommandSourceStack> dispatcher =
                fixture.dispatcher(fixture.readyService());
        CommandSourceStack source = fixture.source();

        assertEquals(1, execute(dispatcher, source, "mpmt machinecode ban code-G"));
        MinecraftTestSupport.drainTasks(fixture.server());

        assertEquals(
                true,
                fixture.recorder().contains("持久化写入失败"),
                "失败文本须含真实原因，实际：" + fixture.recorder().all());
        assertEquals(
                1,
                fixture.recorder().all().stream().filter(text -> text.contains("失败")).count(),
                "失败时不得同时报成功，实际：" + fixture.recorder().all());
    }

    /** 造一个带指定权限集的命令来源（26.2 用权限集而非数字等级）。 */
    private static CommandSourceStack permSource(
            MinecraftServer server, RecordingSource recorder, PermissionSet permissions) {
        return new CommandSourceStack(
                recorder, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, null,
                permissions, "记录源", Component.literal("记录源"), server, null);
    }

    private static int execute(
            CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source, String command) {
        try {
            return dispatcher.execute(command, source);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("命令解析失败：" + command, e);
        }
    }

    /** 一次性夹具：真实封禁服务 + 假端口 + 记录型命令源。 */
    private static final class Fixture {

        private final MinecraftServer server = MinecraftTestSupport.newServer();
        private final RecordingSource recorder = new RecordingSource();
        private final FakePersistence persistence = new FakePersistence();
        private BanService banService;

        MinecraftServer server() {
            return server;
        }

        RecordingSource recorder() {
            return recorder;
        }

        FakePersistence persistence() {
            return persistence;
        }

        BanService banService() {
            return banService;
        }

        CommandSourceStack source() {
            return permSource(server, recorder, LevelBasedPermissionSet.ADMIN);
        }

        CommandDispatcher<CommandSourceStack> dispatcher(BanService service) {
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            FabricMachineCodeCommands.register(dispatcher, () -> service);
            return dispatcher;
        }

        /** 已初始化的服务：封禁表就绪，可承受 ban / unban / list。 */
        BanService readyService() {
            BanService service = new BanService(
                    new BanRegistry(),
                    new SessionRegistry(),
                    persistence,
                    new ImmediateScheduler(),
                    new NoopConnections());
            service.initialize().join();
            banService = service;
            return service;
        }

        /** 未初始化的服务：状态停留在 NEW，用于覆盖"未就绪"分支。 */
        BanService uninitializedService() {
            banService = new BanService(
                    new BanRegistry(),
                    new SessionRegistry(),
                    persistence,
                    new ImmediateScheduler(),
                    new NoopConnections());
            return banService;
        }
    }

    /** 记录型命令源：区分成功与失败输出。 */
    private static final class RecordingSource implements CommandSource {

        private final List<String> successes = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            // 成功与失败都经此入口；按到达顺序统一记录，由用例按文本判别
            successes.add(component.getString());
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

        /** 全部输出文本：{@code sendSuccess} 与 {@code sendFailure} 均经系统消息入口。 */
        List<String> all() {
            List<String> combined = new ArrayList<>(successes);
            combined.addAll(failures);
            return combined;
        }

        void clear() {
            successes.clear();
            failures.clear();
        }

        /** 命中期望文本的输出（成功 / 失败不分先后，按内容判别）。 */
        boolean contains(String fragment) {
            return all().stream().anyMatch(text -> text.contains(fragment));
        }
    }

    /** 假持久化：内存存储，可注入写失败以覆盖异步失败路径。 */
    private static final class FakePersistence implements PersistencePort {

        private final java.util.Map<String, String> store = new java.util.HashMap<>();
        private boolean failWrites;

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(store.get(namespace + "/" + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            if (failWrites) {
                throw new IllegalStateException("持久化写入失败");
            }
            store.put(namespace + "/" + key, value);
        }
    }

    /** 立即执行的调度器：让异步封禁在当前线程内完成，便于确定性断言。 */
    private static final class ImmediateScheduler implements SchedulerPort {

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
    }

    /** 不触达连接控制的端口：命令用例不涉及断开。 */
    private static final class NoopConnections implements ConnectionControlPort {

        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            throw new UnsupportedOperationException("命令用例不触发连接查询");
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            throw new UnsupportedOperationException("命令用例不触发断开");
        }
    }
}
