package top.wcpe.mc.mpmt.core.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.Feature;
import top.wcpe.mc.mpmt.core.runtime.RuntimeContext;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.reliability.ResyncCoordinator;

/**
 * 客户端网络装配特性（L1，FR-19 / FR-28）：把平台注入的 {@link TransportPort} 装配成客户端收发栈——
 * {@link PacketDispatcher}、{@link HandshakeClientService} 与 {@link HeartbeatService}。
 *
 * <p><b>平台无关</b>：各客户端加载器注入传输端口并登记本特性，即复用同一份客户端网络装配
 * （"逻辑写一次"，ADR-0001）。平台在每次物理连接建立后调用 {@link #startHandshake()}，本特性据此维护连接代次。
 *
 * <p><b>生命周期</b>：首次握手完成不重同步；后续连接代次握手成功后自动请求一次重同步。停用时关闭收包服务并清理状态。
 */
public final class ClientNetworkFeature implements Feature {

    private static final String NAME = "client-network";

    private final String modVersion;
    private final MachineCodeProvider machineCodeProvider;
    private final LongSupplier revisionProvider;

    private HandshakeClientService handshakeClient;
    private HeartbeatService heartbeatService;
    private PacketDispatcher dispatcher;
    private ResyncCoordinator resyncCoordinator;
    private AtomicBoolean active;
    private long connectionGeneration;
    private long acceptedGeneration;
    private boolean initialHandshakeCompleted;

    public ClientNetworkFeature(String modVersion, MachineCodeProvider machineCodeProvider) {
        this(modVersion, machineCodeProvider, () -> 0L);
    }

    public ClientNetworkFeature(
            String modVersion, MachineCodeProvider machineCodeProvider, LongSupplier revisionProvider) {
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion 不能为空");
        this.machineCodeProvider = Objects.requireNonNull(machineCodeProvider, "machineCodeProvider 不能为空");
        this.revisionProvider = Objects.requireNonNull(revisionProvider, "revisionProvider 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized void onEnable(RuntimeContext context) {
        TransportPort transport = context.port(TransportPort.class);
        this.dispatcher = new PacketDispatcher(transport, new PacketCodec());
        this.resyncCoordinator = ResyncCoordinator.forClient(dispatcher);
        this.active = new AtomicBoolean(true);
        this.heartbeatService = new HeartbeatService(dispatcher);
        this.handshakeClient =
                new HandshakeClientService(
                        dispatcher,
                        modVersion,
                        machineCodeProvider,
                        this::onHandshakeAccepted,
                        active::get,
                        this::currentConnectionGeneration);
        clearConnectionState();
    }

    @Override
    public synchronized void onDisable(RuntimeContext context) {
        if (active != null) {
            active.set(false);
        }
        if (heartbeatService != null) {
            heartbeatService.close();
        }
        active = null;
        heartbeatService = null;
        handshakeClient = null;
        resyncCoordinator = null;
        dispatcher = null;
        clearConnectionState();
    }

    /** 手动请求服务端重发自 {@code sinceRevision} 起的权威状态（保留 FR-24 兼容入口）。 */
    public synchronized void requestResync(long sinceRevision) {
        resyncCoordinator().requestResync(sinceRevision);
    }

    /** 客户端收发管线（启用后可取，供平台注册收包处理器，如 HUD 渲染）。 */
    public synchronized PacketDispatcher dispatcher() {
        if (dispatcher == null) {
            throw new IllegalStateException("客户端网络特性尚未启用");
        }
        return dispatcher;
    }

    /** 物理连接建立后发起握手，并推进连接代次。 */
    public synchronized void startHandshake() {
        HandshakeClientService service = handshakeClient();
        connectionGeneration++;
        service.startHandshake();
    }

    /** 客户端握手服务（启用后可取，供平台读取握手结果 / 状态）。 */
    public synchronized HandshakeClientService handshakeClient() {
        if (handshakeClient == null) {
            throw new IllegalStateException("客户端网络特性尚未启用");
        }
        return handshakeClient;
    }

    /** 客户端心跳响应器（启用后可取）。 */
    public synchronized HeartbeatService heartbeatService() {
        if (heartbeatService == null) {
            throw new IllegalStateException("客户端网络特性尚未启用");
        }
        return heartbeatService;
    }

    private synchronized void onHandshakeAccepted() {
        if (connectionGeneration == 0L || acceptedGeneration == connectionGeneration) {
            return;
        }
        acceptedGeneration = connectionGeneration;
        if (!initialHandshakeCompleted) {
            initialHandshakeCompleted = true;
            return;
        }
        resyncCoordinator().requestResync(revisionProvider.getAsLong());
    }

    private ResyncCoordinator resyncCoordinator() {
        if (resyncCoordinator == null) {
            throw new IllegalStateException("客户端网络特性尚未启用");
        }
        return resyncCoordinator;
    }

    private synchronized long currentConnectionGeneration() {
        return connectionGeneration;
    }

    private void clearConnectionState() {
        connectionGeneration = 0L;
        acceptedGeneration = 0L;
        initialHandshakeCompleted = false;
    }
}
