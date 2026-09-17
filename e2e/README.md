# MPMT A 车道：mc-testkit E2E

真实 Paper / Folia 服务端 + 桩插件 + mineflayer 机器人的 **Bukkit/Folia 辅助车道**（与 B 主 lane 的自有 gametest 客户端分轨）。
桩只写业务场景，协议胶水来自 mc-testkit 共享构件 `harness-core`。

## 目录

| 路径 | 说明 |
|---|---|
| `harness/` | 服务端桩插件 + `mcTestkit { }` 拓扑声明（**根构建子模块** `:e2e:harness`，ADR-0026：无自有 `settings.gradle.kts` / wrapper） |
| `bot/` | mineflayer 机器人（编排经 Gradle 属性 `mcTestkit.botDir` 定位，根 `gradle.properties` 默认 `e2e/bot`） |

## 怎么跑

```bash
# Paper 1.20.1 smoke：被测插件与桩由接线自动构建并注入，无需导出任何环境变量
./gradlew :runMcTestkitSmoke

# Folia 1.20.1 smoke（场景 smoke-folia）
./gradlew :runMcTestkitFoliaSmoke

# 版本矩阵其余格（Paper 1.21.1 / 26.2）：先构建该版本产品 jar，再用环境变量覆盖被测插件
./gradlew :platform:bukkit:1.21.1:shadowJar
MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR="$PWD/platform/bukkit/1.21.1/build/libs/mpmt-bukkit-1.21.1-$(cat VERSION).jar" \
  ./gradlew :e2e:harness:e2eSmoke1211

# 只构建桩 / 只准备某场景的运行目录
./gradlew :e2e:harness:build
./gradlew :e2e:harness:prepareE2eSmoke
```

生成任务沿用 mc-testkit 约定（`docs/API.md §3.2`）：`prepareE2eSmoke` / `e2eSmoke` / `e2eSmokeFolia` /
`e2eSmoke1211` / `e2eSmoke262`；根侧入口别名 `runMcTestkitSmoke` / `runMcTestkitFoliaSmoke` 在仓库根执行，
任务名与分组是契约。

## 版本矩阵（CI 并行 + 聚合报告）

| 格 | 后端 | 场景 / 任务 | 被测产品 jar |
|---|---|---|---|
| 1 | Paper 1.20.1 | `smoke` / `:e2e:harness:e2eSmoke` | `:platform:bukkit:1.20.1:shadowJar` |
| 2 | Folia 1.20.1 | `smoke-folia` / `:e2e:harness:e2eSmokeFolia` | 同上 |
| 3 | Paper 1.21.1 | `smoke1211` / `:e2e:harness:e2eSmoke1211` | `:platform:bukkit:1.21.1:shadowJar` |
| 4 | Paper 26.2 | `smoke262` / `:e2e:harness:e2eSmoke262` | `:platform:bukkit:26.2:shadowJar` |

`.github/workflows/ci.yml` 的 `e2e-matrix` 逐格并行跑（每格自建本格产品 jar，用
`MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR` 注入），`e2e-report` 汇总各格 `results/*.properties`
写 workflow summary，任一格非 PASS 即失败。矩阵器只依赖远端 WCPE Releases 的 mc-testkit 与
harness-core，**不需要**本地发布或克隆 mc-testkit 源码。

## 接线契约（见 `harness/build.gradle.kts`）

- **被测插件**：默认按**绝对路径**指向 `:platform:bukkit:1.20.1:shadowJar` 产物
  `platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-<version>.jar`（`<version>` 取根 `VERSION`），
  并在生成的 `prepareE2e*` / `e2e*` 上显式 `dependsOn(":platform:bukkit:1.20.1:shadowJar")`——框架不会替外部工程接生产者。
  矩阵其余格用环境变量 `MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR` 覆盖为该格的产品 jar（此时不再接 1.20.1 的图）。
- **桩 jar**：同工程 `jar` 产物同样按绝对路径注入，依赖一并显式接线。
- **不适用自测模式（self-jar）**：该模式只在「未声明 `pluginUnderTest` 且本工程有 `jar` 任务」时注入**本模块** jar；
  本仓被测插件是另一个模块（`:platform:bukkit:1.20.1`）的 shadowJar，故必须显式声明绝对路径。
- **协议层不重复实现**：契约 env 读取、结果文件原子写出、serve 空闲、`E2E_READY` 控制消息、Paper/Folia 调度兼容
  均由 `harness-core` 的 `McTestkitHarnessPlugin` 提供，桩只保留 smoke / smoke-folia 两条业务判定。

## 契约

- 场景 id 三处一致：编排 `scenario("<id>")` = 桩 `ScenarioName` = bot action。
- 结果文件键 `status` / `message`（桩写出，编排 verify **只认这个文件**判 PASS/FAIL）。
- 框架冻结 env 前缀 `MC_TESTKIT_E2E_*`；本机默认**不需要**手工导出插件 jar 路径，只有矩阵格用同名环境变量覆盖。
- 桩 `plugin.yml` **不声明** `api-version`（声明 1.20 会拒载旧版服务端，声明 1.8 会被 1.20+ 拒绝）。
- smoke 要求被测插件 `MultiPlatformModTemplate` 已启用（禁假绿）。
- 版本矩阵格自建并注入**本格**产品 jar；26.2 格的 Paper 由 mc-testkit 自行解析下载（E2E 冒烟不承担
  REALSERVER262 冻结 build 71 那条权威门的职责，两者互不替代）。

## 与 B 车道关系

- **B**：全服务端 + 各 loader 自有 gametest/acceptance 客户端进服 + `AcceptanceReport` / `RESULT PASS`。
- **A**：mc-testkit 起真实 Paper/Folia + bot/桩，结果文件 `status=PASS`；**不**替代 B 的 mod 客户端主门禁。
