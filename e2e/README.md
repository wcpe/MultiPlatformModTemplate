# MPMT A 车道：mc-testkit E2E

从 [mc-testkit](https://github.com/wcpe/mc-testkit) `template/` 照抄，供 **Bukkit/Folia bot 辅车道**（与 B 主 lane 的自有 gametest 客户端分轨）。

## 目录

| 路径 | 说明 |
|---|---|
| `harness/` | 服务端桩插件（独立 Gradle 工程，**不** include 进根 settings） |
| `bot/` | mineflayer 机器人 |

## 构建与注入

```bash
# 1) 产品插件
./gradlew :platform:bukkit:1.20.1:shadowJar

# 2) 桩（独立工程）
./gradlew -p e2e/harness jar

# 3) 导出路径（Git Bash / 本机绝对路径均可）
export MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR="$PWD/platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-<version>.jar"
# 以 build/libs 实际产物名为准；或指向 shadow 输出
export HARNESS_JAR="$PWD/e2e/harness/build/libs/mc-testkit-e2e-harness-1.0.0-SNAPSHOT.jar"

# 4) smoke（无 bot，桩校验被测插件 MultiPlatformModTemplate 已启用）
./gradlew runMcTestkitSmoke -PmcTestkit.botDir=e2e/bot

# Folia 后端 smoke
./gradlew runMcTestkitFoliaSmoke -PmcTestkit.botDir=e2e/bot
```

根 `build.gradle.kts` 已 `id("top.wcpe.mc-testkit") version "0.5.1"`（`pluginManagement` 仓库 `maven.wcpe.top`）+ `mcTestkit { backend paper/folia; scenario smoke/smoke-folia }`。  
**不** `includeBuild` 本机 sibling；他人克隆 / CI 只依赖网络仓库坐标。

## 与 B 车道关系

- **B**：全服务端 + **各 loader 自有 gametest/acceptance 客户端**进服 + `AcceptanceReport`/`RESULT PASS`。
- **A**：mc-testkit 起真实 Paper/Folia + **bot/桩**，结果文件 `status=PASS`；**不**替代 B 的 mod 客户端主门禁。

## 契约

- 场景 id 三处一致：DSL / 桩 `ScenarioName` / bot action。
- 冻结 env 前缀 `MC_TESTKIT_E2E_*`；结果键 `status`/`message`。
- smoke 已改为要求被测插件 `MultiPlatformModTemplate` 启用（禁假绿）。
