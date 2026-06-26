package top.wcpe.mc.mpmt.platform.bukkit.capability;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.spi.Capability;

/**
 * Bukkit 调度端口选用单测（FR-13 / ADR-0013）：穷举按 {@code FeatureGate} 能力位选 SchedulerPort 实现——
 * Folia（{@code REGION_SCHEDULER}）选区域调度、非 Folia 选主线程调度。
 *
 * <p>仅验证<b>选用</b>（构造期不触 Folia API，故 MockBukkit 可承载两分支）；真实 Folia 区域调度行为属实机维度
 * （MockBukkit 不支持区域调度，realserver 由用户在真实 Folia 服确认，ADR-0013：调度端口须按平台写契约测试）。
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

        SchedulerPort port = BukkitSchedulers.create(plugin, capability -> false);

        assertInstanceOf(BukkitSchedulerPort.class, port, "无 REGION_SCHEDULER 应退化为主线程调度");
    }

    @Test
    @DisplayName("Folia 环境（REGION_SCHEDULER）：选用区域调度 FoliaSchedulerPort")
    void Folia环境选区域调度() {
        MockBukkit.mock();
        MockPlugin plugin = MockBukkit.createMockPlugin();

        SchedulerPort port =
                BukkitSchedulers.create(
                        plugin, capability -> capability == Capability.REGION_SCHEDULER);

        assertInstanceOf(FoliaSchedulerPort.class, port, "探测到 Folia 区域调度应选区域调度端口");
    }
}
