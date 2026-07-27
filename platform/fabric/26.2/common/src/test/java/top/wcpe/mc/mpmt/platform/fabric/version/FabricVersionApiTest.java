package top.wcpe.mc.mpmt.platform.fabric.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fabric 无 MC 类型的版本 API 契约。 */
class FabricVersionApiTest {

    @Test
    @DisplayName("产品通道由无 MC 类型值对象统一定义")
    void 通道单一真源() {
        assertEquals("mpmt:main", FabricChannels.PRODUCT.toString());
    }

    @Test
    @DisplayName("运行期实际版本必须与构建选中目标严格相等")
    void 实际版本不符失败快() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> FabricVersions.requireExactMatch("26.2", "1.20.1"));

        assertTrue(error.getMessage().contains("expected=26.2"));
        assertTrue(error.getMessage().contains("actual=1.20.1"));
    }

    @Test
    @DisplayName("选中工厂只暴露当前构建目标的适配器")
    void 选中工厂匹配构建目标() {
        FabricVersionAdapter adapter = SelectedFabricVersionFactory.create();
        assertEquals(System.getProperty("mpmt.test.minecraftVersion"), adapter.minecraftVersion());
    }
}
