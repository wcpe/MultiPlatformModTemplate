package top.wcpe.mc.mpmt.platform.fabric.version.v1_21;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;

/** Fabric 1.21.1 服务端类型化载荷裸字节适配器。 */
public final class V1_21ServerNetwork implements FabricServerNetwork {

    private static final int MAX_PAYLOAD = 1048576;

    private final V1_21PayloadRegistration registration;

    V1_21ServerNetwork(V1_21PayloadRegistration registration) {
        this.registration = registration;
    }

    @Override
    public void registerReceiver(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        ServerPlayNetworking.registerGlobalReceiver(
                registration.type(),
                (payload, context) ->
                        handler.accept(new FabricConnectionHandle(context.player()), payload.data()));
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((FabricConnectionHandle) connection).player();
        ServerPlayNetworking.send(player, registration.payload(data));
    }

    @Override
    public int maxPayloadSize() {
        return MAX_PAYLOAD;
    }
}
