package top.wcpe.mc.mpmt.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.feature.Feature;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/**
 * 特性的 {@link java.util.ServiceLoader} 自动发现（ADR-0028 D6）。
 *
 * <p>测试资源 {@code src/test/resources/META-INF/services/top.wcpe.mc.mpmt.kernel.feature.Feature}
 * 声明了 {@link DiscoveredTestFeature}，因此本类同时验证「services 文件被正确读取」这条链路——
 * 这正是各功能域模块注册自身的机制。
 */
class FeatureDiscoveryTest {

    /** 由测试资源中的 services 文件声明的特性；必须 public + 公开无参构造。 */
    public static final class DiscoveredTestFeature implements Feature {

        @Override
        public String name() {
            return "discovered-test-feature";
        }

        @Override
        public void onEnable(RuntimeContext context) {
            // 发现测试不关心启用行为
        }
    }

    /** 另一个同名特性，用于验证重名快速失败。 */
    private static final class SameNameFeature implements Feature {

        @Override
        public String name() {
            return "discovered-test-feature";
        }

        @Override
        public void onEnable(RuntimeContext context) {
            // 无操作
        }
    }

    @Test
    @DisplayName("discover 从 META-INF/services 自动登记特性")
    void discover从services文件登记特性() {
        FeatureRegistry registry = new FeatureRegistry();

        int count = registry.discover(getClass().getClassLoader());

        // services 文件只声明了 DiscoveredTestFeature 一个，因此这里可以精确断言 1。
        // 写成 >=1 会漏掉「重复登记同一特性」这类缺陷。
        assertEquals(1, count, "services 文件只声明了一个特性，应恰好登记一个");
        List<String> names = new ArrayList<>();
        for (Feature feature : registry.features()) {
            names.add(feature.name());
        }
        assertTrue(names.contains("discovered-test-feature"), "应包含 services 文件声明的特性，实际：" + names);
    }

    @Test
    @DisplayName("discover 与已登记特性重名时快速失败")
    void discover遇到重名特性时失败() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new SameNameFeature());

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.discover(getClass().getClassLoader()),
                "同名特性重复登记必须快速失败，避免静默覆盖");
    }

    @Test
    @DisplayName("discover 传入空类加载器时快速失败")
    void discover拒绝空类加载器() {
        FeatureRegistry registry = new FeatureRegistry();

        assertThrows(NullPointerException.class, () -> registry.discover(null));
    }

    @Test
    @DisplayName("registry 不可变视图：外部修改不影响内部状态")
    void features返回只读视图() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.discover(getClass().getClassLoader());
        List<Feature> snapshot = registry.features();
        int sizeBefore = snapshot.size();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new SameNameFeature()));
        assertEquals(sizeBefore, registry.features().size(), "外部对返回列表的修改不得影响注册表");
    }

    @Test
    @DisplayName("discoverLenient 收集实例化失败但不中断其它域")
    void discoverLenient收集失败项() {
        FeatureRegistry registry = new FeatureRegistry();
        List<String> failures = new ArrayList<>();

        int count = registry.discoverLenient(getClass().getClassLoader(), failures);

        assertNotNull(failures, "失败收集列表不得为空引用");
        assertEquals(1, count, "唯一声明的特性实例化正常，应被登记");
    }
}
