package top.wcpe.mc.mpmt.acceptance.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 用例注册去重与稳定排序（ADR-0014）。 */
class ServerGameTestRegistryTest {

    private static ServerGameTest test(String suite, String id) {
        return new ServerGameTest() {
            @Override
            public String suite() {
                return suite;
            }

            @Override
            public String id() {
                return id;
            }

            @Override
            public void run(ServerGameTestContext context) {
                // 测试只关心登记 / 排序，不执行
            }
        };
    }

    @Test
    @DisplayName("按 suite 再 id 稳定排序")
    void 排序() {
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        registry.register(test("acceptance", "b"));
        registry.register(test("acceptance", "a"));
        registry.register(test("aaa", "z"));

        List<ServerGameTest> all = registry.all();
        assertEquals("aaa/z", all.get(0).suite() + "/" + all.get(0).id());
        assertEquals("acceptance/a", all.get(1).suite() + "/" + all.get(1).id());
        assertEquals("acceptance/b", all.get(2).suite() + "/" + all.get(2).id());
    }

    @Test
    @DisplayName("同 suite/id 重复登记即拒")
    void 重复登记() {
        ServerGameTestRegistry registry = new ServerGameTestRegistry();
        registry.register(test("acceptance", "x"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(test("acceptance", "x")));
    }
}
