package top.wcpe.mc.mpmt.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.ServerMock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitSchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitChannels;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;
import top.wcpe.mc.mpmt.platform.bukkit.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;

class BukkitVersionAssemblyTest {

    @AfterEach
    void 拆除Mock() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void 平台入口使用注入的L4适配器装配网络() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        AtomicBoolean loaded = new AtomicBoolean();
        BukkitVersionAdapter adapter =
                new BukkitVersionAdapter() {
                    @Override
                    public SupportedVersion version() {
                        return SupportedVersion.V1_20;
                    }

                    @Override
                    public BukkitChannels channels() {
                        return new BukkitChannels("mpmt:main");
                    }

                    @Override
                    public SchedulerPort createScheduler(Plugin p, boolean regionScheduler) {
                        return new BukkitSchedulerPort(p);
                    }

                    @Override
                    public void executeGlobal(Plugin p, Runnable task) {
                        p.getServer().getScheduler().runTask(p, task);
                    }
                };
        BukkitPlatformBootstrap bootstrap =
                new BukkitPlatformBootstrap(
                        pluginContext -> {
                            loaded.set(true);
                            return adapter;
                        });
        MpmtRuntime runtime = new MpmtRuntime();

        bootstrap.assemble(
                new PlatformAssemblyContext().register(Plugin.class, plugin), runtime);

        assertTrue(loaded.get(), "平台入口必须加载 L4 适配器");
        assertEquals(Messenger.MAX_MESSAGE_SIZE, runtime.ports().get(TransportPort.class).maxPayloadSize());
        assertTrue(server.getMessenger().isOutgoingChannelRegistered(plugin, "mpmt:main"));
        assertTrue(server.getMessenger().isIncomingChannelRegistered(plugin, "mpmt:main"));
    }
}
