package top.wcpe.mc.mpmt.platform.fabric.gametest.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;

/**
 * 冒烟验收场景（realserver，ADR-0014）：打通"等程序化客户端连入 → 给客户端排程一步验证"的最小链路。
 *
 * <p>当前仅验证服务端驱动 + 客户端联调链路本身（客户端验证器在 Round G 落地）；后续随真实特性扩展为
 * "服务端驱动真实 API + 客户端断言"的完整场景。经 {@code ServiceLoader} 被驱动引导发现。
 */
public final class SmokeServerScenario extends ServerScenario {

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "smoke";
    }

    /** 等客户端连入超时：realserver 客户端冷启动 + 连入需更长余量，放宽到 3 分钟。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 180_000L;

    @Override
    public void run(ServerGameTestContext context) {
        // 等程序化客户端连入并控制通道就绪（冷启动余量）
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);
        // 给客户端排程一步冒烟验证并等回报（非 OK 即 FAIL）
        runClientStep("smoke-ready", "{}");
    }
}
