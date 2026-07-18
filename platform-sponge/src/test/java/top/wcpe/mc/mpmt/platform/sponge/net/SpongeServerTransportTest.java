package top.wcpe.mc.mpmt.platform.sponge.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.network.ServerPlayerConnection;
import org.spongepowered.api.network.channel.ChannelBuf;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.api.network.channel.raw.play.RawPlayDataChannel;
import org.spongepowered.api.network.channel.raw.play.RawPlayDataHandler;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

class SpongeServerTransportTest {

    private static final PluginContainer CONTAINER = proxy(PluginContainer.class, SpongeServerTransportTest::defaultResult);

    @Test
    void 旧版Play回调转交裸字节与玩家句柄() {
        AtomicReference<Class<?>> connectionType = new AtomicReference<>();
        AtomicReference<RawPlayDataHandler<?>> registeredHandler = new AtomicReference<>();
        RawDataChannel channel = channel(connectionType, registeredHandler);
        SpongeServerTransport transport = new SpongeServerTransport(channel, CONTAINER);
        AtomicReference<ConnectionHandle> receivedConnection = new AtomicReference<>();
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        transport.onReceive((connection, data) -> {
            receivedConnection.set(connection);
            receivedData.set(data);
        });

        UUID playerId = UUID.randomUUID();
        byte[] payload = new byte[] {1, 2, 3, 4};
        trigger(registeredHandler.get(), buffer(payload, new AtomicInteger()), connection(playerId));

        assertSame(ServerPlayerConnection.class, connectionType.get());
        assertEquals(new SpongeConnectionHandle(playerId), receivedConnection.get());
        assertArrayEquals(payload, receivedData.get());
    }

    @Test
    void 未注入接收器时安全丢弃() {
        AtomicReference<RawPlayDataHandler<?>> registeredHandler = new AtomicReference<>();
        new SpongeServerTransport(channel(new AtomicReference<>(), registeredHandler), CONTAINER);
        AtomicInteger readCount = new AtomicInteger();

        assertDoesNotThrow(() ->
                trigger(
                        registeredHandler.get(),
                        buffer(new byte[] {9, 8, 7}, readCount),
                        connection(UUID.randomUUID())));
        assertEquals(0, readCount.get());
    }

    private static RawDataChannel channel(
            AtomicReference<Class<?>> connectionType,
            AtomicReference<RawPlayDataHandler<?>> registeredHandler) {
        RawPlayDataChannel play = proxy(RawPlayDataChannel.class, (proxy, method, args) -> {
            if (method.getName().equals("addHandler") && args.length == 2) {
                connectionType.set((Class<?>) args[0]);
                registeredHandler.set((RawPlayDataHandler<?>) args[1]);
            }
            return defaultResult(proxy, method, args);
        });
        return proxy(RawDataChannel.class, (proxy, method, args) ->
                method.getName().equals("play") ? play : defaultResult(proxy, method, args));
    }

    private static ChannelBuf buffer(byte[] payload, AtomicInteger readCount) {
        return proxy(ChannelBuf.class, (proxy, method, args) -> {
            if (method.getName().equals("available")) {
                return payload.length;
            }
            if (method.getName().equals("readBytes")) {
                readCount.incrementAndGet();
                return payload.clone();
            }
            return defaultResult(proxy, method, args);
        });
    }

    private static ServerPlayerConnection connection(UUID playerId) {
        ServerPlayer player = proxy(ServerPlayer.class, (proxy, method, args) ->
                method.getName().equals("uniqueId") ? playerId : defaultResult(proxy, method, args));
        return proxy(ServerPlayerConnection.class, (proxy, method, args) ->
                method.getName().equals("player") ? player : defaultResult(proxy, method, args));
    }

    @SuppressWarnings("unchecked")
    private static void trigger(
            RawPlayDataHandler<?> handler, ChannelBuf buffer, ServerPlayerConnection connection) {
        ((RawPlayDataHandler<ServerPlayerConnection>) handler).handlePayload(buffer, connection);
    }

    private static Object defaultResult(Object proxy, java.lang.reflect.Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return objectResult(proxy, method, args);
        }
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static Object objectResult(Object proxy, java.lang.reflect.Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return proxy.getClass().getInterfaces()[0].getSimpleName() + "代理";
            default:
                return null;
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
