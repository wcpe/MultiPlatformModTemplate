package top.wcpe.mc.mpmt.platform.sponge;

import com.google.inject.Inject;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.plugin.builtin.jvm.Plugin;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/**
 * Sponge 插件入口（{@code @Plugin}）：构造阶段（{@link ConstructPluginEvent}）驱动平台装配——经本插件类加载器
 * ServiceLoader 发现并装配唯一活跃平台、启用运行时。
 *
 * <p>用本类的类加载器（Sponge 插件类加载器）做 ServiceLoader 发现，确保扫到本 jar 的 services（ADR-0002 注意项）。
 * 当前为<b>工具链基座</b>：仅装配 + 启用 + 记活跃平台；服务端 TransportPort（Sponge {@code RawDataChannel}）、能力示例、
 * acceptance 随后续里程碑增量（FR-20/FR-26/FR-27/FR-23）。Sponge 注入 log4j2 {@link Logger}。
 */
@Plugin("mpmt")
public final class MpmtSpongePlugin {

    private final Logger logger;

    @Inject
    MpmtSpongePlugin(final Logger logger) {
        this.logger = logger;
    }

    @Listener
    public void onConstruct(final ConstructPluginEvent event) {
        MpmtRuntime runtime = new MpmtRuntime();
        // 通用装配：发现并装配唯一活跃平台（进程级单一活跃绑定见 ADR-0008 / FR-25）
        PlatformProvider.boot(getClass().getClassLoader(), runtime);
        runtime.enable();
        logger.info("MPMT 已装配并启用，活跃平台：{}", PlatformProvider.get().platformId());
    }
}
