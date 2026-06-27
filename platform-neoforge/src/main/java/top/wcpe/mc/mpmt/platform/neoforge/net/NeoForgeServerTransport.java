package top.wcpe.mc.mpmt.platform.neoforge.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.simple.SimpleChannel;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * NeoForge 服务端传输适配（L3，FR-20）：用 NeoForge {@link SimpleChannel} 实现 L0 {@link TransportPort} 的服务端方向。
 *
 * <p><b>NeoForge 20.2 的网络是 Forge 系</b>（{@code NetworkRegistry}/{@code SimpleChannel}/{@code NetworkEvent}，
 * payload 注册 API 属 20.3+），故本类与 Forge 的 SimpleChannel 版几乎 1:1，仅包名 {@code net.minecraftforge.*}→
 * {@code net.neoforged.*}。NeoForge 运行期官方 Mojmap、无 SRG/reobf（区别于 Forge）。SimpleChannel 加消息索引帧字节、
 * 只适用 <b>NeoForge↔NeoForge</b>（realserver 目标，无需 Mixin）；NeoForge↔Bukkit 异构互通（FR-11②）随后续按 ADR-0018
 * 的裸字节 + Mixin 方案补。
 *
 * <p>单一 {@code RawMessage} 透传 {@code byte[]}；{@code consumerMainThread} 按收发端分流：服务端收
 * （{@code ctx.getSender()!=null}）回 {@link #onReceive}；客户端收回 {@link #setClientReceiver} 注入的处理器
 * （由客户端代理在 {@code Dist.CLIENT} 注入，如 HUD，FR-27）。1.20.2 单锚点无版本差异、不引入 vX_Y 子层。
 */
public final class NeoForgeServerTransport implements TransportPort {

    /** 1.20.2 自定义载荷字节上限。 */
    private static final int MAX_PAYLOAD = 1048576;
    /** 通道网络协议版本。 */
    private static final String PROTOCOL_VERSION = "1";
    /** 单一原始字节消息判别 id。 */
    private static final int RAW_MESSAGE_ID = 0;

    private final SimpleChannel channel;

    /** 服务端收包回调；启用特性时经 {@link #onReceive} 注入。 */
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;
    /** 客户端收包回调；由客户端代理在 {@code Dist.CLIENT} 经 {@link #setClientReceiver} 注入（如 HUD）。 */
    private volatile Consumer<byte[]> clientReceiver;

    public NeoForgeServerTransport(String namespace, String path) {
        this.channel =
                NetworkRegistry.newSimpleChannel(
                        new ResourceLocation(namespace, path),
                        () -> PROTOCOL_VERSION,
                        version -> true,
                        version -> true);
        registerRawMessage();
    }

    private void registerRawMessage() {
        channel.messageBuilder(RawMessage.class, RAW_MESSAGE_ID)
                .encoder((msg, buf) -> buf.writeByteArray(msg.data))
                .decoder(buf -> new RawMessage(buf.readByteArray()))
                .consumerMainThread(
                        (msg, ctx) -> {
                            // NeoForge 20.2 的 consumerMainThread 直接传 Context（Forge 1.20.1 传 Supplier）
                            ServerPlayer sender = ctx.getSender();
                            if (sender != null) {
                                BiConsumer<ConnectionHandle, byte[]> handler = receiveHandler;
                                if (handler != null) {
                                    handler.accept(new NeoForgeConnectionHandle(sender), msg.data);
                                }
                            } else {
                                Consumer<byte[]> client = clientReceiver;
                                if (client != null) {
                                    client.accept(msg.data);
                                }
                            }
                            ctx.setPacketHandled(true);
                        })
                .add();
    }

    /** 注入客户端收包处理器（仅 {@code Dist.CLIENT} 调用，收 S2C 产品字节，如 HUD）。 */
    public void setClientReceiver(Consumer<byte[]> receiver) {
        this.clientReceiver = Objects.requireNonNull(receiver, "receiver 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((NeoForgeConnectionHandle) connection).player();
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

    /** 原始字节消息载体（SimpleChannel 要求消息为类型化对象，仅透传 byte[]）。 */
    private static final class RawMessage {
        private final byte[] data;

        RawMessage(byte[] data) {
            this.data = data;
        }
    }
}
