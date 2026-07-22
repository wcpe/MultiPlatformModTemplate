package top.wcpe.mc.mpmt.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;

class BukkitMachineCodeCommandTest {

    @AfterEach
    void 拆除Mock() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void 封禁命令委托BanService并展示成功() {
        ServerMock server = MockBukkit.mock();
        ConsoleCommandSenderMock sender = server.getConsoleSender();
        MemoryPersistence persistence = new MemoryPersistence();
        BanService service = service(persistence);
        service.initialize().join();
        BukkitMachineCodeCommand command =
                new BukkitMachineCodeCommand(service, new ImmediateScheduler(), server);

        command.onCommand(
                sender,
                bukkitCommand(),
                "mpmt",
                new String[] {"machinecode", "ban", "abc", "测试原因"});

        assertEquals("已封禁机器码：abc", sender.nextMessage());
        assertEquals("测试原因", service.list().get(0).getReason());
    }

    @Test
    void 持久化失败时明确反馈且不提交内存封禁() {
        ServerMock server = MockBukkit.mock();
        ConsoleCommandSenderMock sender = server.getConsoleSender();
        MemoryPersistence persistence = new MemoryPersistence();
        BanService service = service(persistence);
        service.initialize().join();
        persistence.failWrites = true;
        BukkitMachineCodeCommand command =
                new BukkitMachineCodeCommand(service, new ImmediateScheduler(), server);

        command.onCommand(
                sender,
                bukkitCommand(),
                "mpmt",
                new String[] {"machinecode", "ban", "abc"});

        assertTrue(sender.nextMessage().contains("持久化或服务不可用"));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void 默认原因列表解封与用法均可展示() {
        ServerMock server = MockBukkit.mock();
        ConsoleCommandSenderMock sender = server.getConsoleSender();
        BanService service = service(new MemoryPersistence());
        service.initialize().join();
        BukkitMachineCodeCommand command =
                new BukkitMachineCodeCommand(service, new ImmediateScheduler(), server);

        command.onCommand(sender, bukkitCommand(), "mpmt", new String[] {"machinecode", "ban", "abc"});
        assertEquals("已封禁机器码：abc", sender.nextMessage());
        assertEquals("管理员封禁", service.list().get(0).getReason());

        command.onCommand(sender, bukkitCommand(), "mpmt", new String[] {"machinecode", "list"});
        assertTrue(sender.nextMessage().contains("abc - 管理员封禁"));

        command.onCommand(sender, bukkitCommand(), "mpmt", new String[] {"machinecode", "unban", "abc"});
        assertEquals("已解封机器码：abc", sender.nextMessage());

        command.onCommand(sender, bukkitCommand(), "mpmt", new String[] {"machinecode", "list"});
        assertEquals("当前没有机器码封禁", sender.nextMessage());

        command.onCommand(sender, bukkitCommand(), "mpmt", new String[0]);
        assertTrue(sender.nextMessage().startsWith("用法："));
    }

    @Test
    void 未知子命令与错误解封参数展示用法() {
        ServerMock server = MockBukkit.mock();
        ConsoleCommandSenderMock sender = server.getConsoleSender();
        BanService service = service(new MemoryPersistence());
        service.initialize().join();
        BukkitMachineCodeCommand command =
                new BukkitMachineCodeCommand(service, new ImmediateScheduler(), server);

        command.onCommand(sender, bukkitCommand(), "mpmt", new String[] {"machinecode", "other"});
        assertTrue(sender.nextMessage().startsWith("用法："));
        command.onCommand(sender, bukkitCommand(), "mpmt", new String[] {"machinecode", "unban"});
        assertTrue(sender.nextMessage().startsWith("用法："));
    }

    private static Command bukkitCommand() {
        return new Command("mpmt") {
            @Override
            public boolean execute(
                    CommandSender sender, String commandLabel, String[] args) {
                return false;
            }
        };
    }

    private static BanService service(PersistencePort persistence) {
        return new BanService(
                new BanRegistry(),
                new SessionRegistry(),
                persistence,
                new ImmediateScheduler(),
                new NoopConnectionControl());
    }

    private static final class MemoryPersistence implements PersistencePort {
        private final Map<String, String> values = new HashMap<>();
        private boolean failWrites;

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(values.get(namespace + ':' + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            if (failWrites) {
                throw new IllegalStateException("模拟持久化失败");
            }
            values.put(namespace + ':' + key, value);
        }
    }

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

    private static final class NoopConnectionControl implements ConnectionControlPort {
        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            return new EntityRef(UUID.randomUUID());
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            // 测试不建立在线会话，无需断开。
        }
    }
}
