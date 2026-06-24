package top.wcpe.mc.mpmt.core.server;

import java.util.Objects;
import java.util.function.Supplier;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.Feature;
import top.wcpe.mc.mpmt.core.runtime.RuntimeContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;

/**
 * 服务端网络装配特性（L1，FR-19）：把平台注入的 {@link TransportPort} 装配成完整的服务端收发栈——
 * {@link PacketDispatcher} + 握手服务（{@link HandshakeServerService}）+ HUD 下发（{@link HudMessageService}）
 * + 示例往返（Ping → Pong）。
 *
 * <p><b>平台无关</b>：各平台只需注入自己的 {@link TransportPort} 并登记本特性，即复用同一份服务端网络装配
 * （"逻辑写一次"，ADR-0001）。封禁表与会话 id 生成由构造方提供，便于命令 / 其他特性共享同一份状态。
 *
 * <p><b>生命周期</b>：当前按进程级一次性 enable 设计，{@code onDisable} 不解绑 dispatcher 的收包回调
 * （{@link TransportPort} 与 {@link PacketDispatcher} 暂未提供反注册）。<b>不支持热重载 / 重复启用</b>；
 * 若后续支持，需补反注册以免旧回调残留。
 */
public final class ServerNetworkFeature implements Feature {

    private static final String NAME = "server-network";

    private final BanRegistry banRegistry;
    private final Supplier<String> sessionIdSupplier;

    private PacketDispatcher dispatcher;
    private HandshakeServerService handshakeService;
    private HudMessageService hudMessageService;

    public ServerNetworkFeature(BanRegistry banRegistry, Supplier<String> sessionIdSupplier) {
        this.banRegistry = Objects.requireNonNull(banRegistry, "banRegistry 不能为空");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onEnable(RuntimeContext context) {
        TransportPort transport = context.port(TransportPort.class);
        this.dispatcher = new PacketDispatcher(transport, new PacketCodec());
        this.handshakeService = new HandshakeServerService(dispatcher, sessionIdSupplier, banRegistry);
        this.hudMessageService = new HudMessageService(dispatcher);
        // 发包示例（FR-19）：服务端收 Ping 原样回 Pong
        dispatcher.on(
                PacketIds.PING,
                (connection, packet) ->
                        dispatcher.send(connection, new PongPacket(((PingPacket) packet).getNonce())));
    }

    /** 握手服务（启用后可取，供命令 / 其他特性使用）。 */
    public HandshakeServerService handshakeService() {
        return required(handshakeService);
    }

    /** HUD 下发服务（启用后可取）。 */
    public HudMessageService hudMessageService() {
        return required(hudMessageService);
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("服务端网络特性尚未启用");
        }
        return value;
    }
}
