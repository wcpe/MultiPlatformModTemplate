package top.wcpe.mc.mpmt.platform.fabric.version.v1_20;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import top.wcpe.mc.mpmt.platform.fabric.capability.V1_20FabricHud;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricHud;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersionAdapter;

/** Fabric 1.20.1 的网络与 HUD 适配器集合。 */
public final class V1_20FabricVersionAdapter implements FabricVersionAdapter {

    public static final V1_20FabricVersionAdapter INSTANCE = new V1_20FabricVersionAdapter();

    private final Map<FabricChannel, FabricClientNetwork> clients = new ConcurrentHashMap<>();
    private final Map<FabricChannel, FabricServerNetwork> servers = new ConcurrentHashMap<>();
    private volatile FabricHud hud;

    private V1_20FabricVersionAdapter() {
    }

    @Override
    public String minecraftVersion() {
        return "1.20.1";
    }

    @Override
    public FabricClientNetwork clientNetwork(FabricChannel channel) {
        return clients.computeIfAbsent(channel, V1_20ClientNetwork::new);
    }

    @Override
    public FabricServerNetwork serverNetwork(FabricChannel channel) {
        return servers.computeIfAbsent(channel, V1_20ServerNetwork::new);
    }

    @Override
    public FabricHud hud() {
        FabricHud current = hud;
        if (current == null) {
            synchronized (this) {
                current = hud;
                if (current == null) {
                    current = new V1_20FabricHud();
                    hud = current;
                }
            }
        }
        return current;
    }
}
