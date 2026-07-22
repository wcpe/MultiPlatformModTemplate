package top.wcpe.mc.mpmt.platform.spi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 平台启动上下文按类型保存、读取与失败快契约。 */
class PlatformAssemblyContextTest {

    @Test
    void 按声明类型保存并读取() {
        CharSequence value = new StringBuilder("server");
        PlatformAssemblyContext context =
                new PlatformAssemblyContext().register(CharSequence.class, value);

        assertSame(value, context.get(CharSequence.class));
        assertTrue(context.contains(CharSequence.class));
        assertFalse(context.contains(StringBuilder.class));
    }

    @Test
    void 缺失与重复注册失败快() {
        PlatformAssemblyContext context = new PlatformAssemblyContext();
        assertThrows(PlatformAssemblyException.class, () -> context.get(String.class));

        context.register(String.class, "first");
        PlatformAssemblyException error =
                assertThrows(
                        PlatformAssemblyException.class,
                        () -> context.register(String.class, "second"));
        assertTrue(error.getMessage().contains(String.class.getName()));
    }
}
