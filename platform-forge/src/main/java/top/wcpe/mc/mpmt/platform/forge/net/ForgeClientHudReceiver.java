package top.wcpe.mc.mpmt.platform.forge.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.platform.forge.capability.ForgeHudRenderer;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Forge 客户端 HUD 收包接缝（L3 客户端，FR-27）：向 {@link ForgeRawPayloadRouter} 注册产品通道的客户端收包处理器，
 * 把 S2C 裸字节解码为协议包、HUD 包交 {@link ForgeHudRenderer} 渲染。
 *
 * <p><b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）：服务端不得加载本类（引用客户端专有 {@link ForgeHudRenderer}）。
 * 产品通道 {@code mpmt:main} 收包由 {@code Mixin} 在原版 {@code handleCustomPayload} 拦截后经路由分发（ADR-0018），
 * 故对 Forge 服与 Bukkit 服都触发（不再依赖 Forge modded 通道、不卡 vanilla 连接门控）。
 *
 * <p>解码与渲染在 {@code handleCustomPayload}（1.20.1 主/客户端线程）执行，HUD 包交渲染器记录快照（ADR-0013）。
 */
@OnlyIn(Dist.CLIENT)
public final class ForgeClientHudReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    /** 解码器线程安全（注册后只读），复用单例。 */
    private final PacketCodec codec = new PacketCodec();

    private ForgeClientHudReceiver() {
        // 仅经 register 装配
    }

    /**
     * 向裸 payload 路由注册产品通道的客户端入站处理器（FR-27）。
     *
     * @param productChannel 产品通道（{@code mpmt:main}）资源位置，由 {@link ForgeServerTransport#channelId()} 提供
     */
    public static void register(ResourceLocation productChannel) {
        ForgeClientHudReceiver receiver = new ForgeClientHudReceiver();
        ForgeRawPayloadRouter.registerClient(productChannel, receiver::onClientPayload);
        LOGGER.info("MPMT Forge 客户端 HUD 收包已注册（产品通道 mpmt:main）");
    }

    /** 客户端收到服务端字节：解码后 HUD 包交渲染器（非 HUD 包忽略）。 */
    private void onClientPayload(byte[] data) {
        try {
            Packet packet = codec.decode(data);
            if (packet instanceof ServerHudMessagePacket) {
                ForgeHudRenderer.render((ServerHudMessagePacket) packet);
            }
        } catch (RuntimeException e) {
            // 非法 / 截断 / 未知包：丢弃不打断接收器（与产品收发的容错一致）
            LOGGER.warn("丢弃非法 HUD 入站包：{}", e.getMessage());
        }
    }
}
