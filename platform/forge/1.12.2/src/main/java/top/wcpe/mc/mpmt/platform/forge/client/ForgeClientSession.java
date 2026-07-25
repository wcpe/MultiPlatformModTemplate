package top.wcpe.mc.mpmt.platform.forge.client;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudPort;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudSnapshot;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientTransportPort;

/** 每次 1.12.2 PLAY 连接独立的产品网络会话。 */
public final class ForgeClientSession {

    private final ForgeClientTransportPort transport;
    private final ForgeHudPort hud;
    private final String modVersion;
    private final MachineCodeProvider machineCodeProvider;

    private MpmtRuntime runtime;
    private ClientNetworkFeature networkFeature;

    public ForgeClientSession(
            ForgeClientTransportPort transport,
            ForgeHudPort hud,
            String modVersion,
            MachineCodeProvider machineCodeProvider) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.hud = Objects.requireNonNull(hud, "hud 不能为空");
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion 不能为空");
        this.machineCodeProvider =
                Objects.requireNonNull(machineCodeProvider, "machineCodeProvider 不能为空");
    }

    /** 连接事件触发后创建新状态并立即发起产品握手。 */
    public synchronized void join() {
        disconnect();
        MpmtRuntime nextRuntime = new MpmtRuntime();
        nextRuntime.ports().register(TransportPort.class, transport);
        ClientNetworkFeature nextFeature =
                new ClientNetworkFeature(modVersion, machineCodeProvider);
        nextRuntime.features().register(nextFeature);
        nextRuntime.enable();
        hud.register(nextFeature.dispatcher());
        runtime = nextRuntime;
        networkFeature = nextFeature;
        nextFeature.startHandshake();
    }

    /** 断线后清除收包器、握手状态与 HUD 快照。 */
    public synchronized void disconnect() {
        if (runtime != null) {
            runtime.disable();
        }
        transport.clearReceiver();
        hud.clear();
        runtime = null;
        networkFeature = null;
    }

    public synchronized ClientNetworkFeature networkFeature() {
        return networkFeature;
    }

    public ForgeHudSnapshot hudSnapshot() {
        return hud.snapshot();
    }
}
