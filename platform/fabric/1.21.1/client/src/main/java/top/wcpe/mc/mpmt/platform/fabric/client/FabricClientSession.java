package top.wcpe.mc.mpmt.platform.fabric.client;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.fabric.capability.FabricHudRenderer;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricClientTransport;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * Fabric 客户端每次 play 连接的产品网络会话（自 5d3d79d 迁入，改编到 tip 绑定）。
 *
 * <p>JOIN 时立即装配 S2C 收包（HUD 等），握手延后到通道注册就绪后，避免连 Paper 时过早
 * ClientHello 导致 ServerHello 被丢弃。
 */
public final class FabricClientSession {

    private final FabricClientTransport transport;
    private final String modVersion;
    private final MachineCodeProvider machineCodeProvider;

    private MpmtRuntime runtime;
    private ClientNetworkFeature networkFeature;
    private boolean handshakeStarted;

    public FabricClientSession(
            FabricClientNetwork network,
            String modVersion,
            MachineCodeProvider machineCodeProvider) {
        this.transport =
                new FabricClientTransport(Objects.requireNonNull(network, "network 不能为空"));
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion 不能为空");
        this.machineCodeProvider =
                Objects.requireNonNull(machineCodeProvider, "machineCodeProvider 不能为空");
    }

    /**
     * JOIN 时立即装配产品会话与 S2C 收包（HUD 等），但不发起握手。
     *
     * <p>收包器必须尽早挂上，否则服务端在控制通道就绪后立刻下发的产品 S2C 会被丢弃。握手见
     * {@link #startHandshakeWhenReady()}。
     */
    public synchronized void join() {
        disconnect();
        MpmtRuntime nextRuntime = new MpmtRuntime();
        nextRuntime.ports().register(TransportPort.class, transport);
        ClientNetworkFeature nextFeature =
                new ClientNetworkFeature(modVersion, machineCodeProvider);
        nextRuntime.features().register(nextFeature);
        nextRuntime.enable();
        FabricHudRenderer.register(nextFeature.dispatcher());
        runtime = nextRuntime;
        networkFeature = nextFeature;
        handshakeStarted = false;
    }

    /**
     * 在通道注册出站之后发起产品握手（ClientHello）。
     *
     * <p>须在 {@link #join()} 之后调用；连 Paper/Bukkit 时过早发 ClientHello 会导致 ServerHello
     * 因客户端尚未声明监听通道而被丢弃。
     */
    public synchronized void startHandshakeWhenReady() {
        if (networkFeature == null || handshakeStarted) {
            return;
        }
        handshakeStarted = true;
        networkFeature.startHandshake();
    }

    /** 断线后丢弃握手、派发器与 HUD 快照。 */
    public synchronized void disconnect() {
        if (runtime != null) {
            runtime.disable();
        }
        transport.clearReceiver();
        FabricHudRenderer.clear();
        runtime = null;
        networkFeature = null;
        handshakeStarted = false;
    }

    /** 当前 play 会话的产品网络特性；未连接时为空。 */
    public synchronized ClientNetworkFeature networkFeature() {
        return networkFeature;
    }

    /** 最近一次 HUD（验收用）；未连接或尚未收包时为空。 */
    public ServerHudMessagePacket lastHud() {
        return FabricHudRenderer.lastRendered();
    }
}
