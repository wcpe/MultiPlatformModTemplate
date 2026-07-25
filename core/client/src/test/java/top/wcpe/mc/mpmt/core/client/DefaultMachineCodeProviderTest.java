package top.wcpe.mc.mpmt.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 弱标识默认实现：固定输入稳定哈希、不同输入不同哈希、输出为 64 位十六进制（testing-and-quality §2）。 */
class DefaultMachineCodeProviderTest {

    @Test
    @DisplayName("固定输入产出稳定的 64 位十六进制哈希")
    void 固定输入稳定哈希() {
        DefaultMachineCodeProvider provider = new DefaultMachineCodeProvider(() -> "fixed-input");
        String first = provider.get();
        String second = provider.get();

        assertEquals(first, second, "同一输入应产出相同哈希");
        assertEquals(64, first.length(), "SHA-256 hex 应为 64 字符");
        assertTrue(first.matches("[0-9a-f]{64}"), "应为小写十六进制");
    }

    @Test
    @DisplayName("不同输入产出不同哈希")
    void 不同输入不同哈希() {
        assertNotEquals(
                new DefaultMachineCodeProvider(() -> "a").get(),
                new DefaultMachineCodeProvider(() -> "b").get());
    }

    @Test
    @DisplayName("已知向量：SHA-256(\"abc\") 匹配标准值")
    void 已知向量() {
        // SHA-256("abc") 的标准十六进制摘要
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DefaultMachineCodeProvider.sha256Hex("abc"));
    }
}
