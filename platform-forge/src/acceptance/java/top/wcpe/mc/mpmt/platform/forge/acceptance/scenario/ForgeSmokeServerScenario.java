package top.wcpe.mc.mpmt.platform.forge.acceptance.scenario;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.forge.MpmtForgeMod;
import top.wcpe.mc.mpmt.platform.forge.acceptance.ForgeServerGameTestContext;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeConnectionHandle;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Forge 冒烟验收场景（realserver，ADR-0014）：等程序化客户端连入 → 服务端经产品通道发 ACTIONBAR HUD →
 * 客户端验证器断言渲染收到。与 Fabric/Bukkit 冒烟场景同覆盖（同 HUD 文本 / 同步骤 id）。经 {@code ServiceLoader}
 * 被驱动发现。
 *
 * <p><b>经产品传输发 HUD</b>：产品通道 {@code mpmt:main} 为主 mod 注册的 {@link
 * top.wcpe.mc.mpmt.platform.forge.net.ForgeServerTransport SimpleChannel}（会给消息加帧字节），故本场景<b>不</b>裸发
 * {@code ClientboundCustomPayloadPacket}（裸字节客户端 SimpleChannel 解不出），而是取主 mod 的活跃传输 Holder、经其
 * {@code send} 发——既复用同一通道、又确保帧字节与客户端收包一致，且真正走产品收发链路（FR-27）。
 */
public final class ForgeSmokeServerScenario extends ServerScenario {

    /** 等客户端连入超时：realserver Forge dev 客户端冷启动极慢（实测可达 7.5 分钟到主菜单），留充足余量。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 600_000L;

    /** 验收用 HUD 文本（须与客户端验证器期望一致）。 */
    private static final String HUD_TEXT = "验收HUD";

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
        // 服务端经产品传输发一个 HUD（onMain 块内访问在线玩家）：客户端 ForgeHudRenderer 应渲染并记录
        context.onMain(
                () -> {
                    List<ServerPlayer> players = forge.server().getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        byte[] data =
                                new PacketCodec()
                                        .encode(
                                                new ServerHudMessagePacket(
                                                        HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                        // 经主 mod 活跃传输 Holder 发：复用产品 SimpleChannel（帧字节与客户端收包一致，FR-27）
                        MpmtForgeMod.activeTransport()
                                .send(new ForgeConnectionHandle(players.get(0)), data);
                    }
                });

        // 给客户端排程一步冒烟验证并等回报（非 OK 即 FAIL）；验证器同时断言 HUD 已渲染
        runClientStep("smoke-ready", "{}");
    }
}
