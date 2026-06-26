package top.wcpe.mc.mpmt.platform.forge.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.event.EventNetworkChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.platform.forge.capability.ForgeHudRenderer;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Forge 客户端 HUD 收包接缝（L3 客户端，FR-27）：在产品通道 {@code mpmt:main} 上追加客户端入站监听，
 * 把 S2C 裸字节解码为协议包、HUD 包交 {@link ForgeHudRenderer} 渲染。
 *
 * <p><b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）：服务端不得加载本类（引用客户端专有 {@link ForgeHudRenderer}）。
 * <b>不重注册通道</b>——{@link net.minecraftforge.network.NetworkRegistry} 同名通道只能注册一次，产品通道已由
 * {@link ForgeServerTransport} 在构造期注册，本类只在<b>同一</b> {@link EventNetworkChannel} 上 {@code addListener}
 * 收 {@link NetworkEvent.ClientCustomPayloadEvent}（裸字节，与 {@link ForgeServerTransport} 的 S2C 发送对齐）。
 *
 * <p>解码在网络线程；HUD 包记录快照后经 {@code Minecraft#execute} 切客户端线程渲染（ADR-0013）。
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
     * 在产品通道上注册客户端入站监听（FR-27）。
     *
     * @param channel 产品通道（{@code mpmt:main}）实例，由 {@link ForgeServerTransport#channel()} 提供
     */
    public static void register(EventNetworkChannel channel) {
        ForgeClientHudReceiver receiver = new ForgeClientHudReceiver();
        channel.addListener(receiver::onClientPayload);
        LOGGER.info("MPMT Forge 客户端 HUD 收包已注册（产品通道 mpmt:main）");
    }

    /** 客户端收到服务端的裸 payload：解码后 HUD 包交渲染器（非 HUD 包忽略）。 */
    private void onClientPayload(NetworkEvent.ClientCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        byte[] data = readAll(event.getPayload());
        try {
            Packet packet = codec.decode(data);
            if (packet instanceof ServerHudMessagePacket) {
                ForgeHudRenderer.render((ServerHudMessagePacket) packet);
            }
        } catch (RuntimeException e) {
            // 非法 / 截断 / 未知包：丢弃不打断接收器（与产品收发的容错一致）
            LOGGER.warn("丢弃非法 HUD 入站包：{}", e.getMessage());
        }
        ctx.setPacketHandled(true);
    }

    /** 在网络线程立即读出全部可读字节（buf 随后释放）。 */
    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
