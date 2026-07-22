package top.wcpe.mc.mpmt.platform.fabric.gametest.scenario;

import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.platform.fabric.gametest.FabricServerGameTestContext;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * realserver 收尾场景：程序化客户端在线时经产品通道发 ACTIONBAR HUD，客户端断言渲染，证真实网络往返。
 * id 对齐 {@link P1ScenarioMatrix#REAL_ROUND_TRIP}。
 */
public final class RealRoundTripServerScenario extends ServerScenario {

    /** 与客户端验证器约定的 HUD 文本。 */
    public static final String HUD_TEXT = "验收HUD";

    /** 等客户端：覆盖 Gradle 冷启动。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 360_000L;

    @Override
    public String suite() {
        return "acceptance";
    }

    @Override
    public String id() {
        return P1ScenarioMatrix.REAL_ROUND_TRIP.substring(P1ScenarioMatrix.REAL_ROUND_TRIP.indexOf('/') + 1);
    }

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);

        context.onMain(
                () -> {
                    List<ServerPlayer> players =
                            ((FabricServerGameTestContext) context).server().getPlayerList().getPlayers();
                    if (players.isEmpty()) {
                        context.fail("无在线玩家，无法做真实往返");
                    }
                    FriendlyByteBuf buf = PacketByteBufs.create();
                    buf.writeBytes(
                            new PacketCodec()
                                    .encode(new ServerHudMessagePacket(HudKind.ACTIONBAR, HUD_TEXT, "", 0L)));
                    ServerPlayNetworking.send(players.get(0), FabricNetworkBindings.PRODUCT_CHANNEL, buf);
                });

        runClientStep("real-round-trip-ready", "{}");
    }
}
