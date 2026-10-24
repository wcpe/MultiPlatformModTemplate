package top.wcpe.mc.mpmt.platform.neoforge.acceptance.scenario;

import java.util.List;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.kernel.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.platform.neoforge.MpmtNeoForgeMod;

/**
 * 验证真实产品通道完成 ClientHello / ServerHello / ClientIdReport。
 *
 * <p>产品栈在玩家进 PLAY 后自动握手；本场景等服务端观测到握手 ESTABLISHED，
 * 再由客户端断言其产品状态——两端都验，避免只有客户端自证。
 */
public final class ProductHandshakeServerScenario extends ServerScenario {

    private static final int HANDSHAKE_TIMEOUT_TICKS = 200;

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "product-handshake";
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady();
        awaitServerHandshakeEstablished(context);
        runClientStep("verify-handshake", "{}");
    }

    @Override
    protected void awaitServerHandshakeEstablished(ServerGameTestContext context) {
        boolean established =
                context.awaitUntil(
                        HANDSHAKE_TIMEOUT_TICKS, ProductHandshakeServerScenario::anyHandshakeEstablished);
        context.assertTrue(established, "等待产品握手 ESTABLISHED 超时");
    }

    /** 任一在线会话的握手状态机已到达 ESTABLISHED。 */
    private static boolean anyHandshakeEstablished() {
        ServerNetworkFeature feature = MpmtNeoForgeMod.serverNetworkFeature();
        List<SessionRegistry.Session> sessions = feature.sessionRegistry().all();
        for (SessionRegistry.Session session : sessions) {
            if (feature.handshakeService().stateOf(session.getConnection())
                    == HandshakeStateMachine.State.ESTABLISHED) {
                return true;
            }
        }
        return false;
    }
}
