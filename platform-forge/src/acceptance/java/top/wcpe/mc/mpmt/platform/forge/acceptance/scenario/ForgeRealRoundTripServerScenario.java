package top.wcpe.mc.mpmt.platform.forge.acceptance.scenario;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.platform.forge.MpmtForgeMod;
import top.wcpe.mc.mpmt.platform.forge.acceptance.ForgeServerGameTestContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Forge realserver 第 14 项：程序化客户端在线时经产品通道发 ACTIONBAR HUD，客户端断言渲染。
 * id 对齐 {@link P1ScenarioMatrix#REAL_ROUND_TRIP}；前 13 项由进程内回环覆盖。
 */
public final class ForgeRealRoundTripServerScenario extends ServerScenario {

    /** 与客户端验证器约定的 HUD 文本。 */
    public static final String HUD_TEXT = "验收HUD";

    /** 等客户端：Forge dev 冷启动极慢，留充足余量。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 600_000L;

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

        ForgeServerGameTestContext forge = (ForgeServerGameTestContext) context;
        context.onMain(
                () -> {
                    List<ServerPlayer> players = forge.server().getPlayerList().getPlayers();
                    if (players.isEmpty()) {
                        context.fail("无在线玩家，无法做真实往返");
                    }
                    byte[] data =
                            new PacketCodec()
                                    .encode(new ServerHudMessagePacket(HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                    // 经主 mod 活跃传输 Holder 发：复用产品 SimpleChannel（帧字节与客户端收包一致）
                    MpmtForgeMod.sendActive(players.get(0), data);
                });

        runClientStep("real-round-trip-ready", "{}");
    }
}
