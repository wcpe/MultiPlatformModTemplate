package top.wcpe.mc.mpmt.platform.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.network.ServerPlayerConnection;
import org.spongepowered.api.network.channel.raw.RawDataChannel;
import org.spongepowered.api.network.channel.raw.play.RawPlayDataChannel;
import org.spongepowered.api.network.channel.raw.play.RawPlayDataHandler;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.platform.sponge.version.SupportedVersion;
import top.wcpe.mc.mpmt.platform.sponge.version.v1_20.V1_20SpongeServerNetwork;
import top.wcpe.mc.mpmt.platform.spi.PlatformAssemblyContext;

class SpongeVersionAssemblyTest {

    @Test
    void 平台入口使用探测后的RC1365适配器() {
        AtomicBoolean detected = new AtomicBoolean();
        AtomicReference<Class<?>> channelType = new AtomicReference<>();
        AtomicReference<Class<?>> connectionType = new AtomicReference<>();
        RawDataChannel channel = channel(connectionType);
        RegisterChannelEvent event = registerEvent(channelType, channel);
        SpongePlatformBootstrap bootstrap =
                new SpongePlatformBootstrap(
                        () -> {
                            detected.set(true);
                            return SupportedVersion.V1_20;
                        },
                        (version, registerEvent, plugin, connections) -> {
                            assertSame(SupportedVersion.V1_20, version);
                            return new V1_20SpongeServerNetwork(
                                    registerEvent,
                                    plugin,
                                    connections,
                                    proxy(ResourceKey.class, SpongeVersionAssemblyTest::defaultResult));
                        });
        MpmtRuntime runtime = new MpmtRuntime();
        PlatformAssemblyContext context =
                new PlatformAssemblyContext()
                        .register(PluginContainer.class, proxy(PluginContainer.class, SpongeVersionAssemblyTest::defaultResult))
                        .register(RegisterChannelEvent.class, event);

        bootstrap.registerTransport(context, runtime);

        assertTrue(detected.get(), "平台入口必须先探测 Sponge 报告的 MC 版本");
        assertSame(RawDataChannel.class, channelType.get());
        assertSame(ServerPlayerConnection.class, connectionType.get());
        assertEquals(32767, runtime.ports().get(TransportPort.class).maxPayloadSize());
    }

    private static RegisterChannelEvent registerEvent(
            AtomicReference<Class<?>> channelType, RawDataChannel channel) {
        return proxy(RegisterChannelEvent.class, (proxy, method, args) -> {
            if (method.getName().equals("register")) {
                channelType.set((Class<?>) args[1]);
                return channel;
            }
            return defaultResult(proxy, method, args);
        });
    }

    private static RawDataChannel channel(AtomicReference<Class<?>> connectionType) {
        RawPlayDataChannel play = proxy(RawPlayDataChannel.class, (proxy, method, args) -> {
            if (method.getName().equals("addHandler") && args.length == 2) {
                connectionType.set((Class<?>) args[0]);
                assertTrue(args[1] instanceof RawPlayDataHandler);
            }
            return defaultResult(proxy, method, args);
        });
        return proxy(RawDataChannel.class, (proxy, method, args) ->
                method.getName().equals("play") ? play : defaultResult(proxy, method, args));
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
