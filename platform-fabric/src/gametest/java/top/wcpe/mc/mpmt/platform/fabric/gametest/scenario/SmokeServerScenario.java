package top.wcpe.mc.mpmt.platform.fabric.gametest.scenario;

import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.fabric.gametest.FabricServerGameTestContext;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricNetworkBindings;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

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

    /** 验收用 HUD 文本（客户端验证器据此断言渲染收到，FR-27）。 */
    static final String HUD_TEXT = "验收HUD";

    @Override
    public void run(ServerGameTestContext context) {
        // 等程序化客户端连入并控制通道就绪（冷启动余量）
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);

        // 服务端经产品通道发一个 HUD（FR-27）：客户端 FabricHudRenderer 应渲染并记录，供客户端验证器断言
        context.onMain(
                () -> {
                    List<ServerPlayer> players =
                            ((FabricServerGameTestContext) context).server().getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        FriendlyByteBuf buf = PacketByteBufs.create();
                        buf.writeBytes(
                                new PacketCodec()
                                        .encode(new ServerHudMessagePacket(HudKind.ACTIONBAR, HUD_TEXT, "", 0L)));
                        ServerPlayNetworking.send(
                                players.get(0), FabricNetworkBindings.PRODUCT_CHANNEL, buf);
                    }
                });

        // 给客户端排程一步冒烟验证并等回报（非 OK 即 FAIL）；验证器同时断言 HUD 已渲染
        runClientStep("smoke-ready", "{}");
    }
}
