package top.wcpe.mc.mpmt.platform.fabric.gametest.scenario;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.fabric.gametest.FabricServerGameTestContext;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * 冒烟验收场景（realserver，ADR-0014）：经产品 L4 网络下发 HUD，再排程客户端断言。
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

    /** 验收用 HUD 文本（客户端验证器据此断言渲染收到，FR-27）。 */
    static final String HUD_TEXT = "验收HUD";

    @Override
    public void run(ServerGameTestContext context) {
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);

        context.onMain(
                () -> {
                    List<ServerPlayer> players =
                            ((FabricServerGameTestContext) context)
                                    .server()
                                    .getPlayerList()
                                    .getPlayers();
                    if (!players.isEmpty()) {
                        byte[] payload =
                                new PacketCodec()
                                        .encode(
                                                new ServerHudMessagePacket(
                                                        HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                        FabricServerNetwork network =
                                FabricNetworkBindings.productServer(
                                        FabricNetworkBindings.selectedAdapter());
                        network.send(new FabricConnectionHandle(players.get(0)), payload);
                    }
                });

        runClientStep("smoke-ready", "{}");
    }
}
