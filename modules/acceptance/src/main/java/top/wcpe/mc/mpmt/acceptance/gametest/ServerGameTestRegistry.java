package top.wcpe.mc.mpmt.acceptance.gametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 服务端 GameTest 注册表：登记全部用例、按 {@code suite/id} 去重，按 {@code suite} 再 {@code id} 稳定排序输出。
 *
 * <p>仅启动期装配写入；执行期视为只读（由 {@link ServerGameTestRunner} 控制时序）。
 */
public final class ServerGameTestRegistry {

    private final List<ServerGameTest> tests = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    /**
     * 登记一个用例。
     *
     * @throws IllegalArgumentException 同 {@code suite/id} 重复登记
     */
    public void register(ServerGameTest test) {
        Objects.requireNonNull(test, "test 不能为空");
        String key = test.suite() + "/" + test.id();
        if (!keys.add(key)) {
            throw new IllegalArgumentException("用例重复登记：" + key);
        }
        tests.add(test);
    }

    /** 按 {@code suite} 再 {@code id} 排序返回全部用例（稳定、可重复）。 */
    public List<ServerGameTest> all() {
        List<ServerGameTest> sorted = new ArrayList<>(tests);
        sorted.sort(Comparator.comparing(ServerGameTest::suite).thenComparing(ServerGameTest::id));
        return sorted;
    }
}
