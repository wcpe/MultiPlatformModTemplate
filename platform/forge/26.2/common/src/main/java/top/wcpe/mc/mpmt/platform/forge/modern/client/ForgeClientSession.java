package top.wcpe.mc.mpmt.platform.forge.modern.client;

import java.util.Objects;
import net.minecraft.network.Connection;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeClientTransport;
import top.wcpe.mc.mpmt.platform.forge.modern.net.ForgeTypedPayloadChannel;

/** 每次客户端 play 连接独立的产品会话。 */
public final class ForgeClientSession {

    private final ForgeTypedPayloadChannel channel;
    private final ForgeClientHud hud;
    private final String modVersion;
    private final MachineCodeProvider machineCodeProvider;

    private MpmtRuntime runtime;
    private ForgeClientTransport transport;
    private ClientNetworkFeature networkFeature;

    public ForgeClientSession(
            ForgeTypedPayloadChannel channel,
            String modVersion,
            MachineCodeProvider machineCodeProvider) {
        this(channel, new ForgeClientHud(), modVersion, machineCodeProvider);
    }

    ForgeClientSession(
            ForgeTypedPayloadChannel channel,
            ForgeClientHud hud,
            String modVersion,
            MachineCodeProvider machineCodeProvider) {
        this.channel = Objects.requireNonNull(channel, "通道不能为空");
        this.hud = Objects.requireNonNull(hud, "HUD 不能为空");
        this.modVersion = Objects.requireNonNull(modVersion, "mod 版本不能为空");
        this.machineCodeProvider = Objects.requireNonNull(machineCodeProvider, "客户端标识提供器不能为空");
    }

    ForgeClientSession(
            ForgeClientHud hud,
            String modVersion,
            MachineCodeProvider machineCodeProvider) {
        this.channel = null;
        this.hud = Objects.requireNonNull(hud, "HUD 不能为空");
        this.modVersion = Objects.requireNonNull(modVersion, "mod 版本不能为空");
        this.machineCodeProvider = Objects.requireNonNull(machineCodeProvider, "客户端标识提供器不能为空");
    }

    public synchronized void join(Connection connection) {
        disconnect();
        if (channel == null) {
            throw new IllegalStateException("测试会话不能连接真实网络");
        }
        transport = new ForgeClientTransport(channel, connection);
        start(transport);
    }

    synchronized void joinForTest(TransportPort testTransport) {
        disconnect();
        start(Objects.requireNonNull(testTransport, "测试传输不能为空"));
    }

    private void start(TransportPort activeTransport) {
        MpmtRuntime nextRuntime = new MpmtRuntime();
        nextRuntime.ports().register(TransportPort.class, activeTransport);
        ClientNetworkFeature nextFeature = new ClientNetworkFeature(modVersion, machineCodeProvider);
        nextRuntime.features().register(nextFeature);
        nextRuntime.enable();
        hud.register(nextFeature.dispatcher());
        runtime = nextRuntime;
        networkFeature = nextFeature;
        nextFeature.startHandshake();
    }

    public synchronized void disconnect() {
        if (runtime != null) {
            runtime.disable();
        }
        if (transport != null) {
            transport.clearReceiver();
        }
        hud.clear();
        runtime = null;
        transport = null;
        networkFeature = null;
    }

    public synchronized ClientNetworkFeature networkFeature() {
        return networkFeature;
    }

    public ForgeHudSnapshot hudSnapshot() {
        return hud.snapshot();
    }

    public ForgeHudSnapshot actionBarSnapshot() {
        return hud.actionBarSnapshot();
    }
}
