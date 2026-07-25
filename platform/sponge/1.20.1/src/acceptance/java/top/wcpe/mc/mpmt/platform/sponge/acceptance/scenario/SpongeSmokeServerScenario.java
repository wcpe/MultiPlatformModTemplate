package top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario;

import java.util.Optional;
import java.util.UUID;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.network.channel.Channel;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Sponge 冒烟验收场景：等待产品握手后发送 ACTIONBAR HUD，并由真实客户端验证渲染结果。 */
public final class SpongeSmokeServerScenario extends ServerScenario {

    private static final int HANDSHAKE_TIMEOUT_TICKS = 200;
    private static final String HUD_TEXT = "验收HUD";
    private static final ResourceKey PRODUCT_CHANNEL = ResourceKey.of("mpmt", "main");

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return "smoke";
    }

    @Override
    public void run(ServerGameTestContext context) {
        // 使用基类默认 15 分钟：与 capability-first-join 一致，避免客户端冷启超时
        awaitClientReady();
        MpmtSpongePlugin plugin = SpongeScenarioSupport.productPlugin(context);
        boolean established =
                context.awaitUntil(
                        HANDSHAKE_TIMEOUT_TICKS, () -> productHandshakeEstablished(plugin));
        context.assertTrue(established, "等待产品握手 ESTABLISHED 超时");
        UUID playerId = context.onMain(() -> SpongeScenarioSupport.onlinePlayerId(context));
        sendHud(context, playerId);
        runClientStep("smoke-ready", "{}");
    }

    private static boolean productHandshakeEstablished(MpmtSpongePlugin plugin) {
        return Sponge.server().onlinePlayers().stream()
                .map(ServerPlayer::uniqueId)
                .anyMatch(
                        playerId ->
                                plugin.handshakeState(playerId)
                                        == HandshakeStateMachine.State.ESTABLISHED);
    }

    private static void sendHud(ServerGameTestContext context, UUID playerId) {
        context.onMain(
                () -> {
                    ServerPlayer player = Sponge.server().player(playerId).orElse(null);
                    Optional<Channel> channel = Sponge.channelManager().get(PRODUCT_CHANNEL);
                    context.assertTrue(player != null, "发送 HUD 时玩家已离线");
                    context.assertTrue(
                            channel.isPresent() && channel.get() instanceof RawDataChannel,
                            "产品 RawDataChannel 不可用");
                    byte[] data =
                            new PacketCodec()
                                    .encode(
                                            new ServerHudMessagePacket(
                                                    HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                    ((RawDataChannel) channel.get())
                            .play()
                            .sendTo(player, buffer -> buffer.writeBytes(data));
                });
    }
}
