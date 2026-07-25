package top.wcpe.mc.mpmt.platform.fabric.version.v1_21;

import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;

/** Fabric 1.21.1 客户端类型化载荷裸字节适配器。 */
@Environment(EnvType.CLIENT)
public final class V1_21ClientNetwork implements FabricClientNetwork {

    private static final int MAX_PAYLOAD = 1048576;

    private final V1_21PayloadRegistration registration;
    private volatile Consumer<byte[]> receiver;

    V1_21ClientNetwork(V1_21PayloadRegistration registration) {
        this.registration = registration;
        ClientPlayNetworking.registerGlobalReceiver(
                registration.type(),
                (payload, context) -> {
                    Consumer<byte[]> current = receiver;
                    if (current != null) {
                        current.accept(payload.data());
                    }
                });
    }

    @Override
    public void registerReceiver(Consumer<byte[]> handler) {
        receiver = Objects.requireNonNull(handler, "handler 不能为空");
    }

    @Override
    public void clearReceiver() {
        receiver = null;
    }

    @Override
    public void send(byte[] data) {
        ClientPlayNetworking.send(registration.payload(data));
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }
}
