package top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario;

import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin;

/**
 * 验证真实产品通道完成握手与标识上报。
 *
 * <p>服务端等产品握手 ESTABLISHED 后，由 Fabric 客户端伴侣断言握手状态（异构 FR-11②）。
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
        MpmtSpongePlugin plugin = SpongeScenarioSupport.productPlugin(context);
        boolean established =
                context.awaitUntil(
                        HANDSHAKE_TIMEOUT_TICKS, () -> productHandshakeEstablished(plugin));
        context.assertTrue(established, "等待产品握手 ESTABLISHED 超时");
        runClientStep("verify-handshake", "{}");
    }

    private static boolean productHandshakeEstablished(MpmtSpongePlugin plugin) {
        return Sponge.server().onlinePlayers().stream()
                .map(ServerPlayer::uniqueId)
                .anyMatch(
                        playerId ->
                                plugin.handshakeState(playerId)
                                        == HandshakeStateMachine.State.ESTABLISHED);
    }
}
