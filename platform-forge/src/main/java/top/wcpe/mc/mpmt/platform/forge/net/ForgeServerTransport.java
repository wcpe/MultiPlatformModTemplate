package top.wcpe.mc.mpmt.platform.forge.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * Forge 服务端传输适配（L3）：用 Forge {@link SimpleChannel} 实现 L0 {@link TransportPort} 的服务端方向。
 *
 * <p>通道 {@code mpmt:main} 与各平台一致（Bukkit/Fabric 亦此），且接受任意协议版本（含原版/Fabric 客户端），
 * 故 Forge 服务端可与异构客户端互通（FR-11②）。只服务<b>服务端</b>方向；服务端无「无连接发送」。
 * 1.20.1 单锚点下 SimpleChannel API 无版本差异，故不引入 vX_Y 子层（用到才建，scope-discipline）。
 *
 * <p><b>线程契约</b>：入站消息经 {@code consumerMainThread} 在服务端主线程派发，回调上层（线程安全的
 * PacketDispatcher）；上层碰世界 / 领域状态前仍按归属经 SchedulerPort 切线程（ADR-0013）。
 */
public final class ForgeServerTransport implements TransportPort {

    /** 1.20.1 自定义载荷字节上限（vanilla custom payload 上限 2^20）。 */
    private static final int MAX_PAYLOAD = 1048576;
    /** 通道网络协议版本（接受任意对端版本以支持异构互通）。 */
    private static final String PROTOCOL_VERSION = "1";
    /** 单一原始字节消息的判别 id。 */
    private static final int RAW_MESSAGE_ID = 0;

    private final SimpleChannel channel;

    /** 上层收包回调；启用特性时经 {@link #onReceive} 注入，入站早于注入则安全丢弃。 */
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;

    public ForgeServerTransport(String namespace, String path) {
        // 客户端 / 服务端接受版本谓词一律放行（含原版/Fabric 客户端的缺省版本），支持异构互通
        this.channel =
                NetworkRegistry.newSimpleChannel(
                        new ResourceLocation(namespace, path),
                        () -> PROTOCOL_VERSION,
                        version -> true,
                        version -> true);
        registerRawMessage();
    }

    /** 注册唯一的原始字节消息：编解码透传 byte[]，入站在主线程回上层。 */
    private void registerRawMessage() {
        channel.messageBuilder(RawMessage.class, RAW_MESSAGE_ID)
                .encoder((msg, buf) -> buf.writeByteArray(msg.data))
                .decoder(buf -> new RawMessage(buf.readByteArray()))
                .consumerMainThread(
                        (msg, ctxSupplier) -> {
                            NetworkEvent.Context ctx = ctxSupplier.get();
                            ServerPlayer sender = ctx.getSender();
                            BiConsumer<ConnectionHandle, byte[]> handler = receiveHandler;
                            if (sender != null && handler != null) {
                                handler.accept(new ForgeConnectionHandle(sender), msg.data);
                            }
                            ctx.setPacketHandled(true);
                        })
                .add();
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((ForgeConnectionHandle) connection).player();
        channel.send(PacketDistributor.PLAYER.with(() -> player), new RawMessage(data));
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

    /** 原始字节消息载体（SimpleChannel 要求消息为类型化对象，这里仅透传 byte[]）。 */
    private static final class RawMessage {
        private final byte[] data;

        RawMessage(byte[] data) {
            this.data = data;
        }
    }
}
