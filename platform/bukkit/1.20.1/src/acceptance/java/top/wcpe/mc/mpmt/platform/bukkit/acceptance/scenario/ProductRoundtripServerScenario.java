package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;

/**
 * 验证真实产品通道心跳往返（tip：Ping S2C / Pong C2S）。
 *
 * <p>服务端只等客户端就绪并排程断言；客户端复用 Fabric 的
 * {@code ProductRoundtripClientVerifier}（异构 FR-11②）。不在验收源集强引用产品主类
 * （分 jar / 跨类加载器）。
 */
public final class ProductRoundtripServerScenario extends BukkitServerScenario {

    @Override
    public String id() {
        return "product-roundtrip";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);
        runClientStep("verify-roundtrip", "{}");
    }
}
