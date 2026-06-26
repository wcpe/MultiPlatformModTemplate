package top.wcpe.mc.mpmt.platform.forge.net;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * Forge 服务端传输适配（L3）：用<b>裸 vanilla {@code CustomPayload}</b> 实现 L0 {@link TransportPort} 的服务端方向
 * （ADR-0018）。
 *
 * <p><b>裸字节 + Mixin 收包，取代 SimpleChannel</b>：Forge 一旦判定对端为 vanilla 连接（连 Bukkit/Paper 服时）便门控掉
 * modded 通道（SimpleChannel/EventNetworkChannel）的入站派发，导致 Forge 客户端收不到 Bukkit 服的裸包。改为：发包走原版
 * {@link ClientboundCustomPayloadPacket} 裸字节；收包由 {@code Mixin} 在原版 {@code handleCustomPayload} 拦截、经
 * {@link ForgeRawPayloadRouter} 分发——拦截挂在原版包层、不看对端是否 Forge，故<b>同时打通 Forge↔Forge 与 Forge↔Bukkit</b>，
 * 且字节与 Bukkit/Fabric 一致（protocol 单源，ADR-0006）。
 *
 * <p>只服务<b>服务端</b>发送方向；服务端无「无连接发送」。1.20.1 单锚点 API 无版本差异，不引入 vX_Y 子层。
 */
public final class ForgeServerTransport implements TransportPort {

    /** 1.20.1 自定义载荷字节上限（vanilla custom payload 上限 2^20）。 */
    private static final int MAX_PAYLOAD = 1048576;

    /** 产品通道（{@code mpmt:main}）。 */
    private final ResourceLocation channelId;

    /** 服务端收包回调；启用特性时经 {@link #onReceive} 注入。 */
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;

    public ForgeServerTransport(String namespace, String path) {
        this.channelId = new ResourceLocation(namespace, path);
        registerFmlHandshakeMarker();
    }

    /**
     * 注册产品通道为 FML 握手标记（ADR-0018）：本脚手架收发全走裸字节 + Mixin，<b>不</b>用该通道的监听器收数据；
     * 但 Forge↔Forge 连接需有<b>已注册的 mod 通道</b>，FML 才走 modded 握手（否则客户端按 vanilla 连接处理、不应答
     * 服务端的 fml:handshake 注册表同步而登录超时断连）。版本谓词一律放行（含缺省/vanilla），故连 Bukkit/Paper
     * 等非 Forge 服仍可连（其不参与 FML 协商、收发照走 Mixin/裸字节）。须在 mod 构造期注册（注册阶段后会锁定）。
     */
    private void registerFmlHandshakeMarker() {
        NetworkRegistry.newEventChannel(channelId, () -> "1", version -> true, version -> true);
    }

    /** 产品通道资源位置（供客户端 HUD 收包注册同一通道，FR-27）。 */
    public ResourceLocation channelId() {
        return channelId;
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((ForgeConnectionHandle) connection).player();
        FriendlyByteBuf buf =
                new FriendlyByteBuf(Unpooled.buffer(data.length == 0 ? 1 : data.length));
        buf.writeBytes(data);
        player.connection.send(new ClientboundCustomPayloadPacket(channelId, buf));
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        this.receiveHandler = Objects.requireNonNull(handler, "handler 不能为空");
        // 向裸 payload 路由注册服务端收包：Mixin 拦到本通道的裸包→分发到此（ADR-0018）
        ForgeRawPayloadRouter.registerServer(
                channelId,
                (player, data) -> {
                    BiConsumer<ConnectionHandle, byte[]> current = receiveHandler;
                    if (current != null) {
                        current.accept(new ForgeConnectionHandle(player), data);
                    }
                });
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }
}
