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
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
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
    void 平台入口使用探测后的当前锚点适配器() {
        ServerMock server = MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        AtomicBoolean detected = new AtomicBoolean();
        BukkitPlatformBootstrap bootstrap =
                new BukkitPlatformBootstrap(
                        pluginContext -> {
                            detected.set(true);
                            return SupportedVersion.V1_20;
                        });
        MpmtRuntime runtime = new MpmtRuntime();

        bootstrap.assemble(
                new PlatformAssemblyContext().register(Plugin.class, plugin), runtime);

        assertTrue(detected.get(), "平台入口必须先探测运行期 MC 版本");
        assertEquals(Messenger.MAX_MESSAGE_SIZE, runtime.ports().get(TransportPort.class).maxPayloadSize());
        assertTrue(server.getMessenger().isOutgoingChannelRegistered(plugin, "mpmt:main"));
        assertTrue(server.getMessenger().isIncomingChannelRegistered(plugin, "mpmt:main"));
    }
}
