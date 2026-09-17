package com.example.e2e;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import top.wcpe.mc.testkit.harness.McTestkitEnv;
import top.wcpe.mc.testkit.harness.McTestkitHarnessPlugin;

/**
 * MPMT 的 A 车道桩插件（真实 Paper / Folia 服务端内运行）。
 *
 * <p>协议胶水全部来自共享构件 harness-core 的基类 {@link McTestkitHarnessPlugin}——场景 / 结果文件 env
 * 读取、serve 空闲短路、结果文件原子写出 + 关服（键 {@code status} / {@code message}）、
 * {@code E2E_READY} 控制消息、Paper/Folia 兼容调度，均不再由本类重复实现。
 *
 * <p>本类只保留 MPMT 的业务判定：smoke / smoke-folia 都要求被测插件 {@code MultiPlatformModTemplate} 已启用。
 * 桩写成 Java（harness-core 本身是纯 Java 8 构件），避免为它往根构建引入 Kotlin 插件与 stdlib。
 */
public class McTestkitE2eHarnessPlugin extends McTestkitHarnessPlugin {

    /** 被测产品插件名（对齐 platform/bukkit 的 plugin.yml name）。 */
    private static final String PLUGIN_UNDER_TEST_NAME = "MultiPlatformModTemplate";

    /** onEnable 后到判定的延迟（tick）：给被测插件的 onEnable 留出完成时间。 */
    private static final long BOOTSTRAP_DELAY_TICKS = 40L;

    /** 本次场景：编排经 MC_TESTKIT_E2E_SCENARIO 下发优先，缺失回退 config.yml 的 scenario。 */
    private ScenarioName scenario;

    /** 业务启动钩子（基类已备好协议；serve 空闲模式下不会调用本钩子）。 */
    @Override
    protected void onHarnessEnabled() {
        runLater(BOOTSTRAP_DELAY_TICKS, this::runSmokeScenario);
    }

    /** 烟雾场景（无机器人）：服务端已起 + 被测插件已启用即 PASS；缺失或未启用即 FAIL（禁止假绿）。 */
    private void runSmokeScenario() {
        var underTest = getServer().getPluginManager().getPlugin(PLUGIN_UNDER_TEST_NAME);
        if (underTest == null || !underTest.isEnabled()) {
            fail("被测插件未就绪：name=" + PLUGIN_UNDER_TEST_NAME
                + " enabled=" + (underTest == null ? "null" : Boolean.toString(underTest.isEnabled())));
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("server", Bukkit.getServer().getName());
        // backendName 即本后端的声明名（编排下发 MC_TESTKIT_E2E_BACKEND_NAME）
        details.put("backendName", valueOrEmpty(McTestkitEnv.envOrNull(McTestkitEnv.BACKEND_NAME)));
        details.put("pluginUnderTest", PLUGIN_UNDER_TEST_NAME);
        pass("桩与被测插件已就绪，" + scenario().id() + " 通过", details);
    }

    private ScenarioName scenario() {
        if (scenario == null) {
            String envId = McTestkitEnv.scenarioIdOrNull();
            scenario = ScenarioName.from(
                envId != null ? envId : getConfig().getString("scenario", ScenarioName.SMOKE.id()));
        }
        return scenario;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
