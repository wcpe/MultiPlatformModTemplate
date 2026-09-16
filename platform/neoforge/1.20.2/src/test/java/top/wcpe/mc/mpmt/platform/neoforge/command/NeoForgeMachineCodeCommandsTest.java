package top.wcpe.mc.mpmt.platform.neoforge.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.platform.neoforge.NeoForgeTestSupport;

/**
 * NeoForge 原生 Brigadier 机器码封禁命令：注册、三条子命令与失败分支。
 *
 * <p>命令源用替身注入，真实 Brigadier 分发器解析执行；回执经命令源记录后断言。
 */
class NeoForgeMachineCodeCommandsTest {

    @Test
    @DisplayName("注册拒绝空分发器与空服务提供器")
    void 注册空参数防御() {
        assertThrows(NullPointerException.class,
                () -> NeoForgeMachineCodeCommands.register(null, NeoForgeMachineCodeCommandsTest::noopService));
        assertThrows(NullPointerException.class,
                () -> NeoForgeMachineCodeCommands.register(new CommandDispatcher<>(), null));
    }

    @Test
    @DisplayName("注册后 mpmt 命令树含 machinecode 及其三个子命令")
    void 注册命令树结构() {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(readyService());

        assertNotNull(dispatcher.getRoot().getChild("mpmt"));
        assertNotNull(dispatcher.getRoot().getChild("mpmt").getChild("machinecode"));
        assertNotNull(dispatcher.getRoot().getChild("mpmt").getChild("machinecode").getChild("ban"));
        assertNotNull(dispatcher.getRoot().getChild("mpmt").getChild("machinecode").getChild("unban"));
        assertNotNull(dispatcher.getRoot().getChild("mpmt").getChild("machinecode").getChild("list"));
    }

    @Test
    @DisplayName("权限不足的源无法执行 mpmt 命令")
    void 权限不足拒绝执行() {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(readyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(0);

        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("mpmt machinecode list", source));
    }

    @Test
    @DisplayName("封禁服务未就绪时明确失败并返回 0")
    void 服务未就绪明确失败() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher =
                registerWith(unreadyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        assertEquals(0, dispatcher.execute("mpmt machinecode ban ABC", source));
        assertEquals(List.of("封禁服务尚未就绪"), NeoForgeTestSupport.messagesOf(source));
    }

    @Test
    @DisplayName("未就绪时解封与列出同样失败并返回 0")
    void 未就绪解封与列出失败() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(unreadyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        assertEquals(0, dispatcher.execute("mpmt machinecode unban ABC", source));
        assertEquals(0, dispatcher.execute("mpmt machinecode list", source));
        assertEquals(2, NeoForgeTestSupport.messagesOf(source).size());
    }

    @Test
    @DisplayName("空封禁表列出时提示当前没有封禁")
    void 空表列出() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(readyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        assertEquals(1, dispatcher.execute("mpmt machinecode list", source));
        assertEquals(List.of("当前没有机器码封禁"), NeoForgeTestSupport.messagesOf(source));
    }

    @Test
    @DisplayName("带理由封禁后列出显示总数与明细")
    void 带理由封禁后列出() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(readyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        assertEquals(1, dispatcher.execute("mpmt machinecode ban CODE-A 理由甲", source));
        assertEquals(1, dispatcher.execute("mpmt machinecode list", source));

        assertEquals(List.of(
                "已封禁机器码 CODE-A",
                "机器码封禁共 1 条：",
                "CODE-A - 理由甲"), NeoForgeTestSupport.messagesOf(source));
    }

    @Test
    @DisplayName("不带理由的封禁使用默认理由")
    void 不带理由用默认() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(readyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        dispatcher.execute("mpmt machinecode ban CODE-B", source);
        dispatcher.execute("mpmt machinecode list", source);

        assertTrue(NeoForgeTestSupport.messagesOf(source).contains("已封禁机器码 CODE-B"));
        assertEquals("CODE-B - 由管理员封禁", NeoForgeTestSupport.lastMessageOf(source));
    }

    @Test
    @DisplayName("解封后条目从列表消失，列表回到空提示")
    void 解封后列表清空() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(readyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        dispatcher.execute("mpmt machinecode ban CODE-C", source);
        assertEquals(1, dispatcher.execute("mpmt machinecode unban CODE-C", source));
        assertEquals(1, dispatcher.execute("mpmt machinecode list", source));

        assertTrue(NeoForgeTestSupport.messagesOf(source).contains("已解封机器码 CODE-C"));
        assertEquals("当前没有机器码封禁", NeoForgeTestSupport.lastMessageOf(source));
    }

    @Test
    @DisplayName("多条封禁时 list 返回条目数并逐条输出明细")
    void 多条封禁列出() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(readyService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        dispatcher.execute("mpmt machinecode ban CODE-D 理由丁", source);
        dispatcher.execute("mpmt machinecode ban CODE-E 理由戊", source);

        assertEquals(2, dispatcher.execute("mpmt machinecode list", source));
        assertEquals("机器码封禁共 2 条：", NeoForgeTestSupport.messagesOf(source).get(2));
        assertEquals("CODE-E - 理由戊", NeoForgeTestSupport.lastMessageOf(source));
    }

    @Test
    @DisplayName("封禁写入失败时回执错误原因而非成功")
    void 封禁失败回执错误() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(failingWriteService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        assertEquals(1, dispatcher.execute("mpmt machinecode ban CODE-F 理由己", source));

        assertEquals(1, NeoForgeTestSupport.messagesOf(source).size());
        assertTrue(NeoForgeTestSupport.lastMessageOf(source).startsWith("封禁操作失败："),
                "实际回执：" + NeoForgeTestSupport.lastMessageOf(source));
    }

    @Test
    @DisplayName("解封写入失败时回执错误原因")
    void 解封失败回执错误() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = registerWith(failingWriteService());
        CommandSourceStack source = NeoForgeTestSupport.newCommandSource(4);

        dispatcher.execute("mpmt machinecode unban CODE-G", source);

        assertTrue(NeoForgeTestSupport.lastMessageOf(source).startsWith("封禁操作失败："),
                "实际回执：" + NeoForgeTestSupport.lastMessageOf(source));
    }

    private static CommandDispatcher<CommandSourceStack> registerWith(BanService service) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        NeoForgeMachineCodeCommands.register(dispatcher, () -> service);
        return dispatcher;
    }

    private static BanService noopService() {
        return readyService();
    }

    /** 已就绪服务：内存读、空写，initialize 后进入 READY。 */
    private static BanService readyService() {
        BanService service = new BanService(new BanRegistry(), new SessionRegistry(),
                NeoForgeTestSupport.emptyPersistence(),
                NeoForgeTestSupport.immediateScheduler(),
                NeoForgeTestSupport.noopConnections());
        service.initialize();
        return service;
    }

    /** 未就绪服务：从不调用 initialize，封禁状态保持初始值。 */
    private static BanService unreadyService() {
        return new BanService(new BanRegistry(), new SessionRegistry(),
                NeoForgeTestSupport.emptyPersistence(),
                NeoForgeTestSupport.immediateScheduler(),
                NeoForgeTestSupport.noopConnections());
    }

    /** 写入必失败的持久化端口：initialize 可读空快照，但封禁提交必然异常。 */
    private static BanService failingWriteService() {
        BanService service = new BanService(new BanRegistry(), new SessionRegistry(),
                new PersistencePort() {
                    @Override
                    public Optional<String> read(String namespace, String key) {
                        return Optional.empty();
                    }

                    @Override
                    public void write(String namespace, String key, String value) {
                        throw new IllegalStateException("持久化写入失败");
                    }
                },
                NeoForgeTestSupport.immediateScheduler(),
                NeoForgeTestSupport.noopConnections());
        service.initialize();
        return service;
    }
}
