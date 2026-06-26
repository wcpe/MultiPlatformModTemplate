package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.util.Collection;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.BukkitServerGameTestContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Bukkit 冒烟验收场景（realserver，ADR-0014）：等程序化客户端连入 → 服务端经产品通道发 ACTIONBAR HUD →
 * 客户端验证器断言渲染收到。与 Fabric 冒烟场景同覆盖（同 HUD 文本 / 同步骤 id），证 Fabric 客户端 ↔ Bukkit
 * 服务端异构互通（FR-11②）。经 {@code ServiceLoader} 被驱动发现。
 */
public final class BukkitSmokeServerScenario extends ServerScenario {

    /** 等客户端连入超时：realserver 客户端冷启动 + 连入需更长余量。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 180_000L;

    /** 验收用 HUD 文本（须与 Fabric 客户端验证器期望一致）。 */
    private static final String HUD_TEXT = "验收HUD";

    /** 产品跨端通道（与各平台一致）。 */
    private static final String PRODUCT_CHANNEL = "mpmt:main";

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

        BukkitServerGameTestContext bukkit = (BukkitServerGameTestContext) context;
        // 服务端经产品通道发一个 HUD（onMain 块内访问在线玩家）：客户端 FabricHudRenderer 应渲染并记录
        context.onMain(
                () -> {
                    Collection<? extends Player> players = bukkit.server().getOnlinePlayers();
                    if (!players.isEmpty()) {
                        byte[] data =
                                new PacketCodec()
                                        .encode(
                                                new ServerHudMessagePacket(
                                                        HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                        players.iterator()
                                .next()
                                .sendPluginMessage(bukkit.plugin(), PRODUCT_CHANNEL, data);
                    }
                });

        // 给客户端排程一步冒烟验证并等回报（非 OK 即 FAIL）；验证器同时断言 HUD 已渲染
        runClientStep("smoke-ready", "{}");
    }
}
