package top.wcpe.mc.mpmt.platform.neoforge;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * NeoForge mod 入口（{@code @Mod}）：构造期驱动平台装配——发现并装配唯一活跃平台、启用运行时。
 *
 * <p>用本类的类加载器（NeoForge mod 加载器）做 ServiceLoader 发现，确保扫到本 mod 的 services（ADR-0002 注意项）。
 * 当前为<b>工具链基座</b>：仅装配 + 启用 + 记活跃平台；TransportPort（NeoForge 原生 {@code PayloadRegistrar}）与
 * 能力 / HUD 示例随后续里程碑增量（FR-20/FR-26/FR-27）。
 */
@Mod("mpmt")
public final class MpmtNeoForgeMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    public MpmtNeoForgeMod() {
        MpmtRuntime runtime = new MpmtRuntime();
        // 通用装配：发现并装配唯一活跃平台（进程级单一活跃绑定见 ADR-0008 / FR-25）
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        runtime.enable();
        LOGGER.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }
}
