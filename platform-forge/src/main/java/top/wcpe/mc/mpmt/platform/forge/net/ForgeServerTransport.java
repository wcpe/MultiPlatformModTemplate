package top.wcpe.mc.mpmt.platform.forge.net;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * Forge 服务端传输适配（L3）：用 Forge {@link EventNetworkChannel} 实现 L0 {@link TransportPort} 的服务端方向。
 *
 * <p>用 <b>EventNetworkChannel + 原始 CustomPayload</b>（而非 SimpleChannel）传<b>裸字节</b>：SimpleChannel 会在
 * payload 前加一个消息索引帧字节，与 Fabric/Bukkit 的裸字节产品协议不兼容；EventNetworkChannel 收到的 payload
 * 即裸字节，发送也用裸 {@link ClientboundCustomPayloadPacket}，故 Forge 服务端可与异构客户端（Fabric 等）按
 * 同一 {@code mpmt:main} 协议互通（FR-11②）。只服务<b>服务端</b>方向；服务端无「无连接发送」。
 *
 * <p>通道接受任意对端版本（含原版/Fabric 客户端缺省版本）。1.20.1 单锚点 API 无版本差异，不引入 vX_Y 子层。
 *
 * <p><b>线程契约</b>：入站事件在网络线程触发，立即读出裸字节交上层（线程安全的 PacketDispatcher）；上层碰世界 /
 * 领域状态前仍按归属经 SchedulerPort 切线程（ADR-0013）。
 */
public final class ForgeServerTransport implements TransportPort {

    /** 1.20.1 自定义载荷字节上限（vanilla custom payload 上限 2^20）。 */
    private static final int MAX_PAYLOAD = 1048576;
    /** 通道网络协议版本（接受任意对端版本以支持异构互通）。 */
    private static final String PROTOCOL_VERSION = "1";

    private final ResourceLocation channelName;
    private final EventNetworkChannel channel;

    /** 上层收包回调；启用特性时经 {@link #onReceive} 注入，入站早于注入则安全丢弃。 */
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;

    public ForgeServerTransport(String namespace, String path) {
        this.channelName = new ResourceLocation(namespace, path);
        // 客户端 / 服务端接受版本谓词一律放行（含原版/Fabric 客户端的缺省版本），支持异构互通
        this.channel =
                NetworkRegistry.newEventChannel(
                        channelName, () -> PROTOCOL_VERSION, version -> true, version -> true);
        channel.addListener(this::onServerPayload);
    }

    /** 服务端收到客户端的裸 payload：读出字节回上层。 */
    private void onServerPayload(NetworkEvent.ServerCustomPayloadEvent event) {
        NetworkEvent.Context ctx = event.getSource().get();
        ServerPlayer sender = ctx.getSender();
        BiConsumer<ConnectionHandle, byte[]> handler = receiveHandler;
        if (sender != null && handler != null) {
            byte[] data = readAll(event.getPayload());
            handler.accept(new ForgeConnectionHandle(sender), data);
        }
        ctx.setPacketHandled(true);
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((ForgeConnectionHandle) connection).player();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(data.length == 0 ? 1 : data.length));
        buf.writeBytes(data);
        // 裸 CustomPayload（无 SimpleChannel 帧），与 Fabric/Bukkit 裸字节互通
        player.connection.send(new ClientboundCustomPayloadPacket(channelName, buf));
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        this.receiveHandler = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }

    /**
     * 暴露已注册的产品通道（{@code mpmt:main}），供客户端 HUD 侧在<b>同一通道</b>追加 {@code
     * ClientCustomPayloadEvent} 监听收 S2C HUD 字节（FR-27）。
     *
     * <p>{@link NetworkRegistry} 同名通道只能注册一次，故客户端不能再 {@code newEventChannel}，只能
     * {@code addListener} 复用本实例。
     */
    public EventNetworkChannel channel() {
        return channel;
    }

    /** 在网络线程立即读出全部可读字节（buf 随后释放）。 */
    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
