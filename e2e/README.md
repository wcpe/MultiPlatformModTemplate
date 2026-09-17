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
# Paper smoke：被测插件与桩由接线自动构建并注入，无需导出任何环境变量
./gradlew :runMcTestkitSmoke

# Folia smoke（场景 smoke-folia）
./gradlew :runMcTestkitFoliaSmoke

# 只构建桩 / 只准备某场景的运行目录
./gradlew :e2e:harness:build
./gradlew :e2e:harness:prepareE2eSmoke
```

生成任务沿用 mc-testkit 约定（`docs/API.md §3.2`）：`prepareE2eSmoke` / `e2eSmoke` / `e2eSmokeFolia`；
根侧入口别名 `runMcTestkitSmoke` / `runMcTestkitFoliaSmoke` 在仓库根执行，任务名与分组是契约。

## 接线契约（见 `harness/build.gradle.kts`）

- **被测插件**：按**绝对路径**指向 `:platform:bukkit:1.20.1:shadowJar` 产物
  `platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-<version>.jar`（`<version>` 取根 `VERSION`），
  并在生成的 `prepareE2e*` / `e2e*` 上显式 `dependsOn(":platform:bukkit:1.20.1:shadowJar")`——框架不会替外部工程接生产者。
- **桩 jar**：同工程 `jar` 产物同样按绝对路径注入，依赖一并显式接线。
- **不适用自测模式（self-jar）**：该模式只在「未声明 `pluginUnderTest` 且本工程有 `jar` 任务」时注入**本模块** jar；
  本仓被测插件是另一个模块（`:platform:bukkit:1.20.1`）的 shadowJar，故必须显式声明绝对路径。
- **协议层不重复实现**：契约 env 读取、结果文件原子写出、serve 空闲、`E2E_READY` 控制消息、Paper/Folia 调度兼容
  均由 `harness-core` 的 `McTestkitHarnessPlugin` 提供，桩只保留 smoke / smoke-folia 两条业务判定。

## 契约

- 场景 id 三处一致：编排 `scenario("<id>")` = 桩 `ScenarioName` = bot action。
- 结果文件键 `status` / `message`（桩写出，编排 verify **只认这个文件**判 PASS/FAIL）。
- 框架冻结 env 前缀 `MC_TESTKIT_E2E_*`；本车道不再要求消费方手工导出插件 jar 路径。
- 桩 `plugin.yml` **不声明** `api-version`（声明 1.20 会拒载旧版服务端，声明 1.8 会被 1.20+ 拒绝）。
- smoke 要求被测插件 `MultiPlatformModTemplate` 已启用（禁假绿）。

## 与 B 车道关系

- **B**：全服务端 + 各 loader 自有 gametest/acceptance 客户端进服 + `AcceptanceReport` / `RESULT PASS`。
- **A**：mc-testkit 起真实 Paper/Folia + bot/桩，结果文件 `status=PASS`；**不**替代 B 的 mod 客户端主门禁。
