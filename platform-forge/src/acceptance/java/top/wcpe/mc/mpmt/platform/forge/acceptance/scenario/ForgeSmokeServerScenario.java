package top.wcpe.mc.mpmt.platform.forge.acceptance.scenario;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.forge.acceptance.ForgeServerGameTestContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Forge 冒烟验收场景（realserver，ADR-0014）：等程序化客户端连入 → 服务端经产品通道发 ACTIONBAR HUD →
 * 客户端验证器断言渲染收到。与 Fabric/Bukkit 冒烟场景同覆盖（同 HUD 文本 / 同步骤 id），证 Fabric 客户端 ↔
 * Forge 服务端异构互通（FR-11②）。经 {@code ServiceLoader} 被驱动发现。
 *
 * <p><b>不重新注册产品通道 {@code mpmt:main}</b>：该通道归主 mod；本场景直接裸 {@link ClientboundCustomPayloadPacket}
 * 发产品 HUD 字节，避免两个 mod 各自注册同名通道导致冲突（裸字节与产品 {@code ForgeServerTransport} 一致）。
 */
public final class ForgeSmokeServerScenario extends ServerScenario {

    /** 等客户端连入超时：realserver 客户端冷启动 + 连入需更长余量。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 180_000L;

    /** 验收用 HUD 文本（须与 Fabric 客户端验证器期望一致）。 */
    private static final String HUD_TEXT = "验收HUD";

    /** 产品跨端通道命名空间（与各平台一致）。 */
    private static final String PRODUCT_NAMESPACE = "mpmt";
    /** 产品跨端通道路径。 */
    private static final String PRODUCT_PATH = "main";

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
        // 等程序化客户端连入并控制通道就绪（冷启动余量）
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);

        ForgeServerGameTestContext forge = (ForgeServerGameTestContext) context;
        // 服务端裸发一个 HUD（onMain 块内访问在线玩家）：客户端 FabricHudRenderer 应渲染并记录
        context.onMain(
                () -> {
                    List<ServerPlayer> players = forge.server().getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        byte[] data =
                                new PacketCodec()
                                        .encode(
                                                new ServerHudMessagePacket(
                                                        HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                        FriendlyByteBuf buf =
                                new FriendlyByteBuf(Unpooled.buffer(data.length == 0 ? 1 : data.length));
                        buf.writeBytes(data);
                        players.get(0)
                                .connection
                                .send(
                                        new ClientboundCustomPayloadPacket(
                                                new ResourceLocation(PRODUCT_NAMESPACE, PRODUCT_PATH), buf));
                    }
                });

        // 给客户端排程一步冒烟验证并等回报（非 OK 即 FAIL）；验证器同时断言 HUD 已渲染
        runClientStep("smoke-ready", "{}");
    }
}
