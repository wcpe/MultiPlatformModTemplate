package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.util.Collection;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.BukkitServerGameTestContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Bukkit realserver 第 14 项：程序化客户端在线时经产品通道发 ACTIONBAR HUD，客户端断言渲染。
 * id 对齐 {@link P1ScenarioMatrix#REAL_ROUND_TRIP}；前 13 项由进程内回环覆盖。
 * 客户端复用 Fabric 验收伴侣的 {@code RealRoundTripClientVerifier}（异构 FR-11②）。
 */
public final class BukkitRealRoundTripServerScenario extends ServerScenario {

    /** 与 Fabric 客户端验证器约定的 HUD 文本。 */
    public static final String HUD_TEXT = "验收HUD";

    /** 等客户端：覆盖 Fabric Gradle 冷启动（实测可达 5–7 分钟）。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 600_000L;

    /** 1.12.2 产品通道（与 V1_12BukkitVersionAdapter / Forge 客户端一致）。 */
    private static final String PRODUCT_CHANNEL = "MPMT";

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

        BukkitServerGameTestContext bukkit = (BukkitServerGameTestContext) context;
        context.onMain(
                () -> {
                    Collection<? extends Player> players = bukkit.server().getOnlinePlayers();
                    if (players.isEmpty()) {
                        context.fail("无在线玩家，无法做真实往返");
                    }
                    byte[] data =
                            new PacketCodec()
                                    .encode(new ServerHudMessagePacket(HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                    players.iterator().next().sendPluginMessage(bukkit.plugin(), PRODUCT_CHANNEL, data);
                });

        runClientStep("real-round-trip-ready", "{}");
    }
}
