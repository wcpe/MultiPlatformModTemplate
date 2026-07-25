package top.wcpe.mc.mpmt.acceptance.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R1–R6 矩阵 required 场景清单（单一真源）。
 *
 * <p>与 {@link AcceptanceReportV2Expectation} / 严格校验器对齐；各平台矩阵轨 SPI 装载须经
 * {@link #allowsInMatrix(String, String)} 过滤，避免把 P1 smoke / real-round-trip 混进矩阵报告。
 */
public final class MatrixScenarioCatalog {

    private static final List<String> COMMON =
            immutable("product-handshake", "product-roundtrip", "client-hud");

    private static final Map<String, List<String>> BY_MATRIX = create();

    private MatrixScenarioCatalog() {
        // 工具类不实例化
    }

    /** 返回矩阵 required 场景 id 列表（不可变）；未知矩阵立即拒绝。 */
    public static List<String> requiredFor(String matrix) {
        List<String> required = BY_MATRIX.get(requireMatrix(matrix));
        if (required == null) {
            throw new IllegalArgumentException("未知验收矩阵：" + matrix);
        }
        return required;
    }

    /** 场景 id 是否属于该矩阵的 required 清单（矩阵轨 SPI 过滤用）。 */
    public static boolean allowsInMatrix(String matrix, String scenarioId) {
        Objects.requireNonNull(scenarioId, "scenarioId 不能为空");
        return requiredFor(matrix).contains(scenarioId);
    }

    private static Map<String, List<String>> create() {
        Map<String, List<String>> map = new HashMap<>();
        map.put("R1", COMMON);
        map.put("R2", COMMON);
        map.put("R3", COMMON);
        map.put("R4", COMMON);
        map.put(
                "R5",
                withAdditional(
                        COMMON,
                        "forge-client-optional",
                        "active-platform-bukkit",
                        "hybrid-forge-bukkit",
                        "server-forge-product-absent"));
        map.put(
                "R6",
                withAdditional(COMMON, "global-scheduler", "region-scheduler", "entity-scheduler"));
        return Collections.unmodifiableMap(map);
    }

    private static String requireMatrix(String matrix) {
        if (matrix == null || matrix.trim().isEmpty()) {
            throw new IllegalArgumentException("matrix 不能为空");
        }
        return matrix.trim();
    }

    private static List<String> withAdditional(List<String> common, String... additional) {
        List<String> scenarios = new ArrayList<>(common);
        scenarios.addAll(Arrays.asList(additional));
        return Collections.unmodifiableList(scenarios);
    }

    private static List<String> immutable(String... scenarios) {
        return Collections.unmodifiableList(Arrays.asList(scenarios));
    }
}
