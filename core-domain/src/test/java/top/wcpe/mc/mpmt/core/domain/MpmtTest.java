package top.wcpe.mc.mpmt.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证 L0 模块可在纯 JVM（JDK 8 工具链）下编译并运行测试。 */
class MpmtTest {

    @Test
    void 命名空间为_mpmt() {
        assertEquals("mpmt", Mpmt.NAMESPACE);
    }
}
