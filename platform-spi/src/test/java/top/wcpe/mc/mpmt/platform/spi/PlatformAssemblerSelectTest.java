package top.wcpe.mc.mpmt.platform.spi;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.spi.fake.FakePlatformBootstrap;

/** 唯一活跃平台选择：零 / 一 / 多入口（对应 testing-and-quality §2「平台发现与装配」）。 */
class PlatformAssemblerSelectTest {

    @Test
    @DisplayName("零平台：启动期失败快")
    void 零平台失败快() {
        assertThrows(PlatformAssemblyException.class,
                () -> PlatformAssembler.selectActive(Collections.emptyList()));
    }

    @Test
    @DisplayName("唯一平台：选中返回")
    void 唯一平台选中() {
        PlatformBootstrap only = new FakePlatformBootstrap();
        assertSame(only, PlatformAssembler.selectActive(Collections.singletonList(only)));
    }

    @Test
    @DisplayName("多入口同时激活：启动期失败快")
    void 多入口失败快() {
        assertThrows(PlatformAssemblyException.class,
                () -> PlatformAssembler.selectActive(
                        Arrays.asList(new FakePlatformBootstrap(), new FakePlatformBootstrap())));
    }
}
