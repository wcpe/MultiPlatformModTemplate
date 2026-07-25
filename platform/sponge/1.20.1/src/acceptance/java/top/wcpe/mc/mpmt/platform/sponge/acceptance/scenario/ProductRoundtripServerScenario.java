package top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario;

import java.util.List;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin;

/**
 * 验证真实产品通道心跳往返（tip：Ping S2C / Pong C2S）。
 *
 * <p>等产品 HeartbeatService 完成一轮探测后会话 RTT ≥ 0；客户端复用 Fabric 的
 * {@code ProductRoundtripClientVerifier}（异构 FR-11②）。
 */
public final class ProductRoundtripServerScenario extends ServerScenario {

    /** 等首轮心跳：服务端默认间隔 100 tick（约 5s），给冷启动与调度余量。 */
    private static final int RTT_TIMEOUT_TICKS = 400;

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "product-roundtrip";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady();
        MpmtSpongePlugin plugin = SpongeScenarioSupport.productPlugin(context);
        boolean hasRtt =
                context.awaitUntil(RTT_TIMEOUT_TICKS, () -> anySessionHasRtt(plugin));
        context.assertTrue(hasRtt, "产品心跳 Ping/Pong 后应写入会话 RTT");
        runClientStep("verify-roundtrip", "{}");
    }

    private static boolean anySessionHasRtt(MpmtSpongePlugin plugin) {
        List<SessionRegistry.Session> sessions =
                plugin.serverNetworkFeature().sessionRegistry().all();
        for (SessionRegistry.Session session : sessions) {
            if (session.getRttMillis() >= 0L) {
                return true;
            }
        }
        return false;
    }
}
