package top.wcpe.mc.mpmt.platform.neoforge.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.platform.neoforge.capability.NeoForgeHudRenderer;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * NeoForge 客户端 HUD 收包接缝（L3 客户端，FR-27）：向产品传输 {@link NeoForgeServerTransport} 注入客户端收包处理器，
 * 把 S2C 字节解码为协议包、HUD 包交 {@link NeoForgeHudRenderer} 渲染。
 *
 * <p><b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）：服务端不得加载本类（引用客户端专有 {@link NeoForgeHudRenderer}）。
 * 产品通道 {@code mpmt:main} 为 {@link net.neoforged.neoforge.network.simple.SimpleChannel}，由
 * {@link NeoForgeServerTransport} 在构造期注册；本类只经 {@link NeoForgeServerTransport#setClientReceiver} 注入
 * 客户端入站处理器（与服务端 S2C 发送对齐），<b>不重注册通道</b>。
 *
 * <p>解码与渲染在 SimpleChannel 的 {@code consumerMainThread} 回调（客户端线程）执行，HUD 包交渲染器记录快照（ADR-0013）。
 */
@OnlyIn(Dist.CLIENT)
public final class NeoForgeClientHudReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    /** 解码器线程安全（注册后只读），复用单例。 */
    private final PacketCodec codec = new PacketCodec();

    private NeoForgeClientHudReceiver() {
        // 仅经 register 装配
    }

    /**
     * 向产品传输注入客户端入站处理器（FR-27）。
     *
     * @param transport 产品服务端传输（持 {@code mpmt:main} SimpleChannel），由 mod 主类构造期传入
     */
    public static void register(NeoForgeServerTransport transport) {
        NeoForgeClientHudReceiver receiver = new NeoForgeClientHudReceiver();
        transport.setClientReceiver(receiver::onClientPayload);
        LOGGER.info("MPMT NeoForge 客户端 HUD 收包已注册（产品通道 mpmt:main）");
    }

    /** 客户端收到服务端字节：解码后 HUD 包交渲染器（非 HUD 包忽略）。 */
    private void onClientPayload(byte[] data) {
        try {
            Packet packet = codec.decode(data);
            if (packet instanceof ServerHudMessagePacket) {
                NeoForgeHudRenderer.render((ServerHudMessagePacket) packet);
            }
        } catch (RuntimeException e) {
            // 非法 / 截断 / 未知包：丢弃不打断接收器（与产品收发的容错一致）
            LOGGER.warn("丢弃非法 HUD 入站包：{}", e.getMessage());
        }
    }
}
