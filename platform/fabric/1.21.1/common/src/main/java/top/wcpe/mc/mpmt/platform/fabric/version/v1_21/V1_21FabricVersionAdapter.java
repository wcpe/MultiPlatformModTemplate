package top.wcpe.mc.mpmt.platform.fabric.version.v1_21;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import top.wcpe.mc.mpmt.platform.fabric.capability.V1_21FabricHud;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricHud;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersionAdapter;

/** Fabric 1.21.1 的类型化网络与 HUD 适配器集合。 */
public final class V1_21FabricVersionAdapter implements FabricVersionAdapter {

    public static final V1_21FabricVersionAdapter INSTANCE = new V1_21FabricVersionAdapter();

    private final Map<FabricChannel, V1_21PayloadRegistration> registrations =
            new ConcurrentHashMap<>();
    private final Map<FabricChannel, FabricClientNetwork> clients = new ConcurrentHashMap<>();
    private final Map<FabricChannel, FabricServerNetwork> servers = new ConcurrentHashMap<>();
    private volatile FabricHud hud;

    private V1_21FabricVersionAdapter() {
    }

    @Override
    public String minecraftVersion() {
        return "1.21.1";
    }

    @Override
    public FabricClientNetwork clientNetwork(FabricChannel channel) {
        return clients.computeIfAbsent(
                channel, ignored -> new V1_21ClientNetwork(registration(channel)));
    }

    @Override
    public FabricServerNetwork serverNetwork(FabricChannel channel) {
        return servers.computeIfAbsent(
                channel, ignored -> new V1_21ServerNetwork(registration(channel)));
    }

    @Override
    public FabricHud hud() {
        FabricHud current = hud;
        if (current == null) {
            synchronized (this) {
                current = hud;
                if (current == null) {
                    current = new V1_21FabricHud();
                    hud = current;
                }
            }
        }
        return current;
    }

    private V1_21PayloadRegistration registration(FabricChannel channel) {
        return registrations.computeIfAbsent(channel, V1_21PayloadRegistration::new);
    }
}
