package top.wcpe.mc.mpmt.platform.forge.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport;
import top.wcpe.mc.mpmt.platform.forge.ForgeMinecraftTestSupport.FakeServerNetwork;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeServerTransport;
import top.wcpe.mc.mpmt.platform.spi.PlatformProvider;

/** Forge 客户端代理：装配客户端网络特性、HUD 接缝与断线清理的真实行为。 */
class ClientProxyTest {

    @BeforeAll
    static void 安装运行期替身() {
        ForgeMinecraftTestSupport.installEmptyModList();
        ForgeMinecraftTestSupport.installClientInstance();
    }

    @BeforeEach
    void 隔离全局状态() {
        ForgeMinecraftTestSupport.resetNetworkRegistry();
        PlatformProvider.deactivate();
    }

    @AfterEach
    void 复原全局状态() {
        ForgeMinecraftTestSupport.resetNetworkRegistry();
        PlatformProvider.deactivate();
    }

    @Test
    @DisplayName("构造拒绝空服务端传输")
    void 构造拒绝空传输() {
        assertThrows(NullPointerException.class, () -> new ClientProxy(null));
    }

    @Test
    @DisplayName("初始化装配唯一客户端网络特性并向验收入口暴露当前特性")
    void 初始化装配客户端网络() {
        FakeServerNetwork network = new FakeServerNetwork();
        ClientProxy proxy = new ClientProxy(new ForgeServerTransport(network));

        proxy.init();

        ClientNetworkFeature feature = ClientProxy.networkFeature();
        assertNotNull(feature);
        assertNotNull(feature.dispatcher());
        assertNotNull(feature.handshakeClient());
        assertSame(feature, ClientProxy.networkFeature());
        assertEquals(network.channelId(), ForgeMinecraftTestSupport.channelOf(proxy));
    }

    @Test
    @DisplayName("重复初始化以最新装配覆盖当前客户端特性")
    void 重复初始化覆盖特性() {
        ClientProxy first = new ClientProxy(ForgeMinecraftTestSupport.newTransport());
        first.init();
        ClientNetworkFeature firstFeature = ClientProxy.networkFeature();

        ClientProxy second = new ClientProxy(ForgeMinecraftTestSupport.newTransport());
        second.init();
        ClientNetworkFeature secondFeature = ClientProxy.networkFeature();

        assertSame(secondFeature, ClientProxy.networkFeature());
        assertNotSame(firstFeature, secondFeature);
    }

    @Test
    @DisplayName("登录后发起握手、登出后清理唯一 dispatcher 的连接状态")
    void 登录登出生命周期() {
        ClientProxy proxy = new ClientProxy(ForgeMinecraftTestSupport.newTransport());
        proxy.init();
        ClientNetworkFeature feature = ClientProxy.networkFeature();

        proxy.onLoggingIn(null);
        // 假服务端传输不可达，握手仍处于未接受状态
        assertFalse(feature.handshakeClient().isAccepted());
        assertNotNull(ForgeMinecraftTestSupport.clientListener());
        proxy.onLoggingOut(null);

        assertSame(feature, ClientProxy.networkFeature());
    }

    @Test
    @DisplayName("活跃特性持有者被清空后读取明确失败")
    void 清空持有者后明确失败() {
        ClientProxy proxy = new ClientProxy(ForgeMinecraftTestSupport.newTransport());
        proxy.init();
        assertNotNull(ClientProxy.networkFeature());

        ForgeMinecraftTestSupport.clearClientFeature(proxy);

        assertThrows(IllegalStateException.class, ClientProxy::networkFeature);
    }
}
