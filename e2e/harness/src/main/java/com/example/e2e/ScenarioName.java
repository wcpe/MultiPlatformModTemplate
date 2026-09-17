package com.example.e2e;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A 车道场景枚举（只有两条烟雾场景）。
 *
 * <p>{@link #id()} 须与桩的业务判定分支、编排 {@code mcTestkit { }} 的场景名用同一个 kebab-case id
 * （三处一致），否则编排下发的 {@code MC_TESTKIT_E2E_SCENARIO} 在桩侧匹配不上。
 */
public enum ScenarioName {
    /** Paper 1.20.1 烟雾：无机器人，仅校验桩自身与被测插件已就绪即 PASS。 */
    SMOKE("smoke"),

    /** Folia 1.20.1 烟雾：与 {@link #SMOKE} 同判定逻辑，后端换成 Folia。 */
    SMOKE_FOLIA("smoke-folia"),

    /** Paper 1.21.1 烟雾：版本矩阵格（CI 并行跑，产品 jar 由该格注入）。 */
    SMOKE_1211("smoke1211"),

    /** Paper 26.2 烟雾：版本矩阵格（CI 并行跑，产品 jar 由该格注入）。 */
    SMOKE_262("smoke262");

    private final String id;

    ScenarioName(String id) {
        this.id = id;
    }

    /** 编排侧使用的场景 id。 */
    public String id() {
        return id;
    }

    /** 按 id 解析场景；未知 id 直接抛中文错误，避免静默跑错场景。 */
    public static ScenarioName from(String id) {
        for (ScenarioName candidate : values()) {
            if (candidate.id.equals(id)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
            "未知 A 车道场景: " + id + "（可选值: "
                + Arrays.stream(values()).map(ScenarioName::id).collect(Collectors.joining(", ")) + "）");
    }
}
