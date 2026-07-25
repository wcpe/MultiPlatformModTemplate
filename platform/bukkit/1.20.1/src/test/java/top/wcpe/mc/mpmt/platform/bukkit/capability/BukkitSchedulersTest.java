package top.wcpe.mc.mpmt.platform.bukkit.capability;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitChannels;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter;
import top.wcpe.mc.mpmt.platform.bukkit.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.spi.Capability;

/**
 * Bukkit 调度端口选用单测（FR-13 / ADR-0013）：经 L4 适配器按 capability 选实现。
 */
class BukkitSchedulersTest {

    @AfterEach
    void 拆除Mock() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("非 Folia 环境：选用主线程调度 BukkitSchedulerPort")
    void 非Folia环境选主线程调度() {
        MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitVersionAdapter adapter = modernAdapter();

        SchedulerPort port = BukkitSchedulers.create(plugin, capability -> false, adapter);

        assertInstanceOf(BukkitSchedulerPort.class, port, "无 REGION_SCHEDULER 应退化为主线程调度");
    }

    @Test
    @DisplayName("Folia 环境（REGION_SCHEDULER）：选用区域调度 FoliaSchedulerPort")
    void Folia环境选区域调度() {
        MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();
        BukkitVersionAdapter adapter = modernAdapter();

        SchedulerPort port =
                BukkitSchedulers.create(
                        plugin, capability -> capability == Capability.REGION_SCHEDULER, adapter);

        assertInstanceOf(FoliaSchedulerPort.class, port, "探测到 Folia 区域调度应选区域调度端口");
    }

    private static BukkitVersionAdapter modernAdapter() {
        return new BukkitVersionAdapter() {
            @Override
            public SupportedVersion version() {
                return SupportedVersion.V1_20;
            }

            @Override
            public BukkitChannels channels() {
                return new BukkitChannels("mpmt:main");
            }

            @Override
            public SchedulerPort createScheduler(Plugin plugin, boolean regionScheduler) {
                if (regionScheduler) {
                    return new FoliaSchedulerPort(plugin);
                }
                return new BukkitSchedulerPort(plugin);
            }

            @Override
            public void executeGlobal(Plugin plugin, Runnable task) {
                plugin.getServer().getScheduler().runTask(plugin, task);
            }
        };
    }
}
