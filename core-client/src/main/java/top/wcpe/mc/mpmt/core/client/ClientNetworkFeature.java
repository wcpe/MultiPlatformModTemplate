package top.wcpe.mc.mpmt.core.client;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.Feature;
import top.wcpe.mc.mpmt.core.runtime.RuntimeContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.reliability.ResyncCoordinator;

/**
 * 客户端网络装配特性（L1，FR-19）：把平台注入的 {@link TransportPort}（客户端方向）装配成客户端收发栈——
 * {@link PacketDispatcher} + 客户端握手服务（{@link HandshakeClientService}）。
 *
 * <p><b>平台无关</b>：各客户端加载器只需注入自己的 {@link TransportPort} 并登记本特性，即复用同一份客户端网络装配
 * （"逻辑写一次"，ADR-0001）。握手在客户端连入服务端后由平台触发 {@link #startHandshake()}（如 Fabric 的连接事件）。
 *
 * <p><b>生命周期</b>：当前按进程级一次性启用，{@code onDisable} 不解绑（同 ServerNetworkFeature），不支持热重载。
 */
public final class ClientNetworkFeature implements Feature {

    private static final String NAME = "client-network";

    private final String modVersion;
    private final MachineCodeProvider machineCodeProvider;

    private HandshakeClientService handshakeClient;
    private PacketDispatcher dispatcher;
    private ResyncCoordinator resyncCoordinator;

    public ClientNetworkFeature(String modVersion, MachineCodeProvider machineCodeProvider) {
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion 不能为空");
        this.machineCodeProvider = Objects.requireNonNull(machineCodeProvider, "machineCodeProvider 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onEnable(RuntimeContext context) {
        TransportPort transport = context.port(TransportPort.class);
        this.dispatcher = new PacketDispatcher(transport, new PacketCodec());
        this.handshakeClient = new HandshakeClientService(dispatcher, modVersion, machineCodeProvider);
        // 重连重同步（FR-24）：装配协调器供 requestResync 发请求；客户端不处理入站重同步请求（handler 空操作）
        this.resyncCoordinator =
                new ResyncCoordinator(dispatcher, (connection, sinceRevision) -> {
                    // 客户端方向不处理入站重同步请求
                });
    }

    /**
     * 重连重同步（FR-24）：请求服务端重发自 {@code sinceRevision} 起的权威状态。
     *
     * <p>由平台在<b>重连 / 重新握手后</b>触发（如 Fabric 的重新 JOIN 事件），与 {@link #startHandshake()} 同为
     * 平台驱动的连接生命周期钩子；{@code sinceRevision} 取客户端已知的最新权威修订号。
     */
    public void requestResync(long sinceRevision) {
        if (resyncCoordinator == null) {
            throw new IllegalStateException("客户端网络特性尚未启用");
        }
        resyncCoordinator.requestResync(sinceRevision);
    }

    /** 客户端收发管线（启用后可取，供平台注册收包处理器，如 HUD 渲染）。 */
    public PacketDispatcher dispatcher() {
        if (dispatcher == null) {
            throw new IllegalStateException("客户端网络特性尚未启用");
        }
        return dispatcher;
    }

    /** 发起握手（客户端连入服务端后由平台触发）。 */
    public void startHandshake() {
        handshakeClient().startHandshake();
    }

    /** 客户端握手服务（启用后可取，供平台读取握手结果 / 状态）。 */
    public HandshakeClientService handshakeClient() {
        if (handshakeClient == null) {
            throw new IllegalStateException("客户端网络特性尚未启用");
        }
        return handshakeClient;
    }
}
