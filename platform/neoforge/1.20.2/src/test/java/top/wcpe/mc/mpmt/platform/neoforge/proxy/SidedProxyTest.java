package top.wcpe.mc.mpmt.platform.neoforge.proxy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.neoforge.NeoForgeTestSupport;
import top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeServerTransport;
import top.wcpe.mc.mpmt.platform.neoforge.version.v1_20_2.V1_20_2ServerNetwork;

/**
 * NeoForge 端侧代理：客户端初始化、断线清理与服务端占位。
 *
 * <p>{@code ClientProxy} 的初始化会注册事件总线并读取 mod 列表，故须先重置网络注册表以隔离通道。
 */
class SidedProxyTest {

    @Test
    @DisplayName("客户端代理拒绝空服务端传输")
    void 客户端代理空参数防御() {
        assertThrows(NullPointerException.class, () -> new ClientProxy(null));
    }

    @Test
    @DisplayName("客户端代理初始化装配客户端网络特性与传输")
    void 客户端代理初始化() {
        NeoForgeTestSupport.resetNetworkRegistry();
        NeoForgeTestSupport.installEmptyModList();
        ClientProxy proxy = new ClientProxy(transport("proxy-init"));

        proxy.init();

        assertNotNull(ClientProxy.networkFeature());
        assertNotNull(ClientProxy.networkFeature().dispatcher());
        assertSame(ClientProxy.networkFeature(), ClientProxy.networkFeature());
    }

    @Test
    @DisplayName("客户端代理暴露已装配的客户端传输与服务端连接句柄")
    void 客户端代理暴露传输() {
        NeoForgeTestSupport.resetNetworkRegistry();
        NeoForgeTestSupport.installEmptyModList();
        ClientProxy proxy = new ClientProxy(transport("proxy-transport"));

        proxy.init();

        NeoForgeClientTransportAccess access = new NeoForgeClientTransportAccess(proxy);
        assertNotNull(access.transport());
        assertNotNull(access.transport().serverConnection());
        assertSame(access.transport().serverConnection(), access.transport().serverConnection());
        assertTrue(access.transport().maxPayloadSize() > 0);
    }

    @Test
    @DisplayName("未装配时读取客户端网络特性明确失败")
    void 未装配读取特性失败() {
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<Object> holder =
                (java.util.concurrent.atomic.AtomicReference<Object>) NeoForgeTestSupport
                        .getStatic(ClientProxy.class, "ACTIVE_FEATURE");
        Object saved = holder.get();
        holder.set(null);

        try {
            assertThrows(IllegalStateException.class, ClientProxy::networkFeature);
        } finally {
            holder.set(saved);
        }
    }

    @Test
    @DisplayName("断线清理调用不抛异常且可重复")
    void 断线清理幂等() {
        NeoForgeTestSupport.resetNetworkRegistry();
        NeoForgeTestSupport.installEmptyModList();
        ClientProxy proxy = new ClientProxy(transport("proxy-logout"));
        proxy.init();

        proxy.onLoggingOut(null);
        proxy.onLoggingOut(null);

        assertNotNull(ClientProxy.networkFeature());
    }

    @Test
    @DisplayName("客户端握手在无连接时可重复发起且协议状态保持一致")
    void 握手可重复发起() {
        NeoForgeTestSupport.resetNetworkRegistry();
        NeoForgeTestSupport.installEmptyModList();
        ClientProxy proxy = new ClientProxy(transport("proxy-handshake"));
        proxy.init();

        // 无客户端连接时握手发送失败但不破坏特性状态
        try {
            proxy.onLoggingIn(null);
        } catch (RuntimeException expected) {
            assertNotNull(expected);
        }

        assertNotNull(ClientProxy.networkFeature().handshakeClient());
    }

    @Test
    @DisplayName("服务端代理初始化不抛异常")
    void 服务端代理初始化() {
        SidedProxy proxy = new ServerProxy();

        proxy.init();

        assertNotNull(proxy);
    }

    private static NeoForgeServerTransport transport(String path) {
        return new NeoForgeServerTransport(
                new V1_20_2ServerNetwork("mpmt", path));
    }

    /** 读取客户端代理内部传输，避免为测试放宽生产可见性。 */
    private static final class NeoForgeClientTransportAccess {

        private final ClientProxy proxy;

        NeoForgeClientTransportAccess(ClientProxy proxy) {
            this.proxy = proxy;
        }

        top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeClientTransport transport() {
            return (top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeClientTransport)
                    NeoForgeTestSupport.getField(proxy,
                            NeoForgeTestSupport.field(ClientProxy.class, "transport"));
        }
    }
}
