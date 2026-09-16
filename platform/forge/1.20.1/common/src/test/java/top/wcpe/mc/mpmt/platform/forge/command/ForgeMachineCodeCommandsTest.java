package top.wcpe.mc.mpmt.platform.forge.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport;
import top.wcpe.mc.mpmt.platform.forge.ForgePortFixtures;

/**
 * Forge 原生 Brigadier 机器码封禁命令：真实注册并经 {@link CommandDispatcher#execute} 触发全部子命令分支。
 *
 * <p>用假命令源收集成功 / 失败回执，断言封禁、解封、列表与"服务未就绪"四条路径的真实输出。
 */
class ForgeMachineCodeCommandsTest {

    @Test
    @DisplayName("注册拒绝空参数")
    void 注册拒绝空参数() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        assertThrows(NullPointerException.class, () -> ForgeMachineCodeCommands.register(null, () -> null));
        assertThrows(NullPointerException.class, () -> ForgeMachineCodeCommands.register(dispatcher, null));
    }

    @Test
    @DisplayName("ban / list / unban 全链路写入真实封禁表并回执中文消息")
    void 封禁解封与列表() throws CommandSyntaxException {
        CommandRecorder recorder = new CommandRecorder();
        BanService service = readyService();
        CommandDispatcher<CommandSourceStack> dispatcher = registered(recorder, service);

        assertEquals(1, dispatcher.execute("mpmt machinecode ban ABC123", recorder.source));
        assertEquals(List.of("已封禁机器码 ABC123"), recorder.flushSuccesses());
        assertTrue(service.isBanned(new MachineCode("ABC123")));

        assertEquals(1, dispatcher.execute("mpmt machinecode list", recorder.source));
        assertEquals(List.of("机器码封禁共 1 条：", "ABC123 - 由管理员封禁"), recorder.flushSuccesses());

        assertEquals(1, dispatcher.execute("mpmt machinecode unban ABC123", recorder.source));
        assertEquals(List.of("已解封机器码 ABC123"), recorder.flushSuccesses());
        assertFalse(service.isBanned(new MachineCode("ABC123")));

        assertEquals(1, dispatcher.execute("mpmt machinecode list", recorder.source));
        assertEquals(List.of("当前没有机器码封禁"), recorder.flushSuccesses());
    }

    @Test
    @DisplayName("带原因封禁使用贪婪原因参数，列表同时回显多条")
    void 带原因封禁与多条列表() throws CommandSyntaxException {
        CommandRecorder recorder = new CommandRecorder();
        BanService service = readyService();
        CommandDispatcher<CommandSourceStack> dispatcher = registered(recorder, service);

        assertEquals(1, dispatcher.execute("mpmt machinecode ban CODE-1 违规使用客户端", recorder.source));
        assertEquals(List.of("已封禁机器码 CODE-1"), recorder.flushSuccesses());
        assertEquals(1, dispatcher.execute("mpmt machinecode ban CODE-2 另一原因", recorder.source));
        recorder.flushSuccesses();

        assertEquals(2, dispatcher.execute("mpmt machinecode list", recorder.source));
        assertEquals(List.of("机器码封禁共 2 条：", "CODE-1 - 违规使用客户端", "CODE-2 - 另一原因"),
                recorder.flushSuccesses());

        List<BanEntry> entries = service.list();
        assertEquals(2, entries.size());
        assertEquals("违规使用客户端", entries.get(0).getReason());
    }

    @Test
    @DisplayName("封禁服务未就绪时全部子命令回执失败且不产生成功消息")
    void 服务未就绪回执失败() throws CommandSyntaxException {
        CommandRecorder recorder = new CommandRecorder();
        CommandDispatcher<CommandSourceStack> dispatcher = registered(recorder, null);

        assertEquals(0, dispatcher.execute("mpmt machinecode ban ANY", recorder.source));
        assertEquals(0, dispatcher.execute("mpmt machinecode unban ANY", recorder.source));
        assertEquals(0, dispatcher.execute("mpmt machinecode list", recorder.source));
        assertEquals(List.of("封禁服务尚未就绪", "封禁服务尚未就绪", "封禁服务尚未就绪"), recorder.failures);
        assertTrue(recorder.flushSuccesses().isEmpty());
    }

    @Test
    @DisplayName("封禁失败时经服务端事件循环回执失败消息（含异常原因）")
    void 封禁失败回执原因() throws CommandSyntaxException {
        CommandRecorder recorder = new CommandRecorder();
        BanService failing = new BanService(
                new BanRegistry(),
                new SessionRegistry(),
                ForgePortFixtures.emptyPersistence(),
                ForgePortFixtures.immediateScheduler(),
                ForgePortFixtures.noopConnections(
                        new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID())));
        failing.initialize().join();

        // 服务已就绪但持久化不可用：写入失败经 complete 回执到命令源
        BanService broken = new BanService(
                new BanRegistry(),
                new SessionRegistry(),
                new top.wcpe.mc.mpmt.core.domain.port.PersistencePort() {
                    @Override
                    public java.util.Optional<String> read(String namespace, String key) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public void write(String namespace, String key, String value) {
                        throw new IllegalStateException("磁盘只读");
                    }
                },
                ForgePortFixtures.immediateScheduler(),
                ForgePortFixtures.noopConnections(
                        new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID())));
        broken.initialize().join();
        CommandDispatcher<CommandSourceStack> brokenDispatcher = registered(recorder, broken);

        assertEquals(1, brokenDispatcher.execute("mpmt machinecode ban LOCKED", recorder.source));
        assertEquals(List.of("封禁操作失败：磁盘只读"), recorder.flushFailures());
        assertFalse(broken.isBanned(new MachineCode("LOCKED")));
    }

    private static BanService readyService() {
        BanService service = new BanService(
                new BanRegistry(),
                new SessionRegistry(),
                ForgePortFixtures.emptyPersistence(),
                ForgePortFixtures.immediateScheduler(),
                ForgePortFixtures.noopConnections(
                        new top.wcpe.mc.mpmt.core.domain.ref.EntityRef(UUID.randomUUID())));
        service.initialize().join();
        return service;
    }

    private static CommandDispatcher<CommandSourceStack> registered(
            CommandRecorder recorder, BanService service) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ForgeMachineCodeCommands.register(dispatcher, () -> service);
        return dispatcher;
    }

    /** 假命令源：记录成功 / 失败回执，并同步执行服务端事件循环提交的任务。 */
    private static final class CommandRecorder {

        private final MinecraftServer server = ForgeMinecraftTestSupport.newServer();
        private final List<String> successes = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final CommandSourceStack source = new CommandSourceStack(
                new net.minecraft.commands.CommandSource() {
                    @Override
                    public void sendSystemMessage(Component message) {
                        successes.add(message.getString());
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
                },
                net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.world.phys.Vec2.ZERO,
                null,
                4,
                "测试命令源",
                Component.literal("测试命令源"),
                server,
                null) {
            @Override
            public void sendSuccess(java.util.function.Supplier<Component> message, boolean broadcastToAdmins) {
                successes.add(message.get().getString());
            }

            @Override
            public void sendFailure(Component message) {
                failures.add(message.getString());
            }
        };

        /** 服务端事件循环替身：排空 pendingRunnables，使 whenComplete 回执同步可见。 */
        private List<String> flushSuccesses() {
            ForgeMinecraftTestSupport.drainPendingTasks(server);
            return drain(successes);
        }

        private List<String> flushFailures() {
            ForgeMinecraftTestSupport.drainPendingTasks(server);
            return drain(failures);
        }

        private static List<String> drain(List<String> target) {
            List<String> snapshot = List.copyOf(target);
            target.clear();
            return snapshot;
        }
    }
}
