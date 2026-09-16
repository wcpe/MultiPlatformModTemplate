package top.wcpe.mc.mpmt.platform.fabric.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.fabric.FabricTestSupport;
import top.wcpe.mc.mpmt.platform.fabric.FabricTestSupport.LoaderStub;

/** Fabric 网络绑定装配点：锚点校验、通道复用与运行期版本不符失败快。 */
class FabricNetworkBindingsTest {

    @Test
    @DisplayName("产品通道经锚点枚举装配，且同通道重复装配返回同一实例")
    void 产品通道装配与复用() {
        String expectedVersion = System.getProperty("mpmt.test.minecraftVersion");
        try (LoaderStub loader = FabricTestSupport.stubMinecraftVersion(expectedVersion)) {
            loader.getClass();
            SupportedVersion version = FabricVersions.detect();

            assertEquals(expectedVersion, version.mcVersion());
            assertSame(
                    FabricNetworkBindings.serverNetwork(version),
                    FabricNetworkBindings.serverNetwork(version),
                    "服务端网络按通道缓存，重复装配必须是同一实例");
            assertSame(
                    FabricNetworkBindings.clientNetwork(version),
                    FabricNetworkBindings.clientNetwork(version));
            assertSame(FabricNetworkBindings.selectedAdapter(), FabricNetworkBindings.selectedAdapter());
        }
    }

    @Test
    @DisplayName("请求锚点与产物锚点不符时失败快，且错误信息同时给出两侧版本")
    void 锚点不符失败快() {
        String packaged = System.getProperty("mpmt.test.minecraftVersion");
        try (LoaderStub loader = FabricTestSupport.stubMinecraftVersion(packaged)) {
            loader.getClass();
            SupportedVersion mismatched = otherAnchor();

            IllegalStateException serverError =
                    assertThrows(
                            IllegalStateException.class,
                            () -> FabricNetworkBindings.serverNetwork(mismatched));
            IllegalStateException clientError =
                    assertThrows(
                            IllegalStateException.class,
                            () -> FabricNetworkBindings.clientNetwork(mismatched));

            for (IllegalStateException error : List.of(serverError, clientError)) {
                assertTrue(error.getMessage().contains("requested=" + mismatched.mcVersion()));
                assertTrue(error.getMessage().contains("packaged=" + packaged));
            }
        }
    }

    @Test
    @DisplayName("锚点与适配器为空均失败快，不静默装配残缺绑定")
    void 空参失败快() {
        assertThrows(NullPointerException.class, () -> FabricNetworkBindings.serverNetwork(null));
        assertThrows(NullPointerException.class, () -> FabricNetworkBindings.clientNetwork(null));
        assertThrows(NullPointerException.class, () -> FabricNetworkBindings.productServer(null));
        assertThrows(NullPointerException.class, () -> FabricNetworkBindings.productClient(null));
    }

    @Test
    @DisplayName("按适配器直接装配产品通道，通道标识为 mpmt:main")
    void 产品通道标识契约() {
        try (LoaderStub loader =
                FabricTestSupport.stubMinecraftVersion(System.getProperty("mpmt.test.minecraftVersion"))) {
            assertEquals(loader.stubbedVersion(), FabricVersions.actualMinecraftVersion());

            FabricVersionAdapter adapter = FabricNetworkBindings.selectedAdapter();

            assertInstanceOf(FabricServerNetwork.class, FabricNetworkBindings.productServer(adapter));
            assertInstanceOf(FabricClientNetwork.class, FabricNetworkBindings.productClient(adapter));
            assertEquals(
                    FabricChannels.PRODUCT.namespace(),
                    FabricNetworkBindings.PRODUCT_CHANNEL.getNamespace());
            assertEquals(FabricChannels.PRODUCT.path(), FabricNetworkBindings.PRODUCT_CHANNEL.getPath());
        }
    }

    private static SupportedVersion otherAnchor() {
        SupportedVersion actual = FabricVersions.detect();
        for (SupportedVersion candidate : SupportedVersion.values()) {
            if (candidate != actual) {
                return candidate;
            }
        }
        throw new IllegalStateException("需要至少两个锚点才能构造不符场景");
    }
}
