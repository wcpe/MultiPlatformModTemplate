package top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario;

import java.util.Optional;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.network.channel.Channel;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerScenario;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Sponge 冒烟验收场景（realserver，ADR-0014）：等程序化客户端连入 → 服务端经产品通道发 ACTIONBAR HUD →
 * 客户端验证器断言渲染收到。与各平台冒烟场景同覆盖（同 HUD 文本 / 同步骤 id），证 Fabric 客户端 ↔ Sponge
 * 服务端异构互通（同 Bukkit 模式）。经 {@code ServiceLoader} 被驱动发现。
 *
 * <p>产品通道由主插件注册，故验收场景经全局 {@code ChannelManager} 取回该通道发包（验收插件不重复注册产品通道）。
 */
public final class SpongeSmokeServerScenario extends ServerScenario {

    /** 等客户端连入超时：realserver 客户端冷启动 + 连入需更长余量。 */
    private static final long CLIENT_READY_TIMEOUT_MS = 180_000L;

    /** 验收用 HUD 文本（须与 Fabric 客户端验证器期望一致）。 */
    private static final String HUD_TEXT = "验收HUD";

    /** 产品跨端通道（与各平台一致，由主插件注册）。 */
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
        // 等程序化客户端连入并控制通道就绪（冷启动余量）
        awaitClientReady(CLIENT_READY_TIMEOUT_MS);

        // 服务端经产品通道发一个 HUD（onMain 块内访问在线玩家）：客户端 FabricHudRenderer 应渲染并记录
        context.onMain(
                () -> {
                    Optional<ServerPlayer> player =
                            Sponge.server().onlinePlayers().stream().findFirst();
                    Optional<Channel> product = Sponge.channelManager().get(PRODUCT_CHANNEL);
                    if (player.isPresent()
                            && product.isPresent()
                            && product.get() instanceof RawDataChannel) {
                        byte[] data =
                                new PacketCodec()
                                        .encode(
                                                new ServerHudMessagePacket(
                                                        HudKind.ACTIONBAR, HUD_TEXT, "", 0L));
                        ((RawDataChannel) product.get())
                                .play()
                                .sendTo(player.get(), buf -> buf.writeBytes(data));
                    }
                });

        // 给客户端排程一步冒烟验证并等回报（非 OK 即 FAIL）；验证器同时断言 HUD 已渲染
        runClientStep("smoke-ready", "{}");
    }
}
