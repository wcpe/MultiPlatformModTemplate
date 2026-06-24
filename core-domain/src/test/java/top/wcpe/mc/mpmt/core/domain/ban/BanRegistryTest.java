package top.wcpe.mc.mpmt.core.domain.ban;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 封禁表 ban/unban/isBanned/list 穷举（testing-and-quality §2「BanRegistry」）。 */
class BanRegistryTest {

    private static MachineCode code(String v) {
        return new MachineCode(v);
    }

    @Test
    @DisplayName("封禁后命中、解封后不命中")
    void 封禁与解封() {
        BanRegistry registry = new BanRegistry();
        MachineCode a = code("aaa");
        assertFalse(registry.isBanned(a));

        registry.ban(a, "作弊");
        assertTrue(registry.isBanned(a));

        registry.unban(a);
        assertFalse(registry.isBanned(a));
    }

    @Test
    @DisplayName("类型相等：同值 MachineCode 视为同一键")
    void 同值标识同键() {
        BanRegistry registry = new BanRegistry();
        registry.ban(code("x"), "原因");
        // 另建同值 MachineCode 应命中（值对象相等）
        assertTrue(registry.isBanned(code("x")));
        assertFalse(registry.isBanned(code("y")));
    }

    @Test
    @DisplayName("list 反映当前封禁、重复封禁覆盖原因")
    void 列表与覆盖() {
        BanRegistry registry = new BanRegistry();
        registry.ban(code("a"), "原因1");
        registry.ban(code("b"), "原因2");
        assertEquals(2, registry.list().size());

        registry.ban(code("a"), "原因1-更新");
        assertEquals(2, registry.list().size());
        BanEntry a = registry.list().stream().filter(e -> e.getCode().equals(code("a"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("原因1-更新", a.getReason());
    }

    @Test
    @DisplayName("解封不存在的标识：无操作不报错")
    void 解封不存在() {
        BanRegistry registry = new BanRegistry();
        registry.unban(code("nope"));
        assertFalse(registry.isBanned(code("nope")));
    }
}
