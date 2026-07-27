package top.wcpe.mc.mpmt.platform.fabric.version.v26_2;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import top.wcpe.mc.mpmt.platform.fabric.capability.V26_2FabricHud;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricHud;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricVersionAdapter;

/** Fabric 26.2 的类型化网络与 HUD 适配器集合。 */
public final class V26_2FabricVersionAdapter implements FabricVersionAdapter {

    public static final V26_2FabricVersionAdapter INSTANCE = new V26_2FabricVersionAdapter();

    private final Map<FabricChannel, V26_2PayloadRegistration> registrations =
            new ConcurrentHashMap<>();
    private final Map<FabricChannel, FabricClientNetwork> clients = new ConcurrentHashMap<>();
    private final Map<FabricChannel, FabricServerNetwork> servers = new ConcurrentHashMap<>();
    private volatile FabricHud hud;

    private V26_2FabricVersionAdapter() {
    }

    @Override
    public String minecraftVersion() {
        return "26.2";
    }

    @Override
    public FabricClientNetwork clientNetwork(FabricChannel channel) {
        return clients.computeIfAbsent(
                channel, ignored -> new V26_2ClientNetwork(registration(channel)));
    }

    @Override
    public FabricServerNetwork serverNetwork(FabricChannel channel) {
        return servers.computeIfAbsent(
                channel, ignored -> new V26_2ServerNetwork(registration(channel)));
    }

    @Override
    public FabricHud hud() {
        FabricHud current = hud;
        if (current == null) {
            synchronized (this) {
                current = hud;
                if (current == null) {
                    current = new V26_2FabricHud();
                    hud = current;
                }
            }
        }
        return current;
    }

    private V26_2PayloadRegistration registration(FabricChannel channel) {
        return registrations.computeIfAbsent(channel, V26_2PayloadRegistration::new);
    }
}
