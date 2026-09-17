# 运维手册：MultiPlatformModTemplate

> 物理布局：`core/` · `platform/<loader>/{api,版本}/` · `modules/`。  
> Gradle 工程名保持稳定（车道工程名带加载器前缀，如 `:platform:fabric:fabric-1.20.1`）；车道目录仍为 `platform/<loader>/<版本>`，由根 `settings.gradle.kts` 的 `projectDir` 映射。

> **入口统一为根构建**：全部平台车道已是根构建普通子模块（ADR-0026），**不存在**车道自有 wrapper / 车道 `settings.gradle.kts` / `-p <车道目录>` 的独立调用方式。所有 Gradle 调用都在仓库根执行，且**根构建须以 JDK 25 守护运行**（26.2 两条车道在配置期硬校验，ADR-0026 决策 4）。

## 1. 构建

> A 车道 mc-testkit 插件固定 `0.9.3`，经 `pluginManagement` 的 `maven.wcpe.top`（WCPE Releases）解析；根 `settings.gradle.kts` 仍保留只覆盖 `top.wcpe.mc-testkit` / `top.wcpe.mc` 两个 group 的 mavenLocal 回退，供本机或 CI 已发布同版制品时离线使用（未发布该版本则回退到远端坐标）。

```bash
# 根 L0–L2 + 平台 api
./gradlew --no-daemon :core:domain:build :core:spi:build
./gradlew --no-daemon :platform:bukkit:bukkit-api:build :platform:fabric:fabric-api:build

# Bukkit 版本产物
./gradlew --no-daemon :platform:bukkit:1.20.1:shadowJar
./gradlew --no-daemon :platform:bukkit:1.12.2:shadowJar
./gradlew --no-daemon :platform:bukkit:1.21.1:shadowJar
./gradlew --no-daemon :platform:bukkit:26.2:shadowJar # Java 25

# Fabric / Forge / Sponge（平台车道均为根子模块，用绝对工程路径）
./gradlew --no-daemon :platform:fabric:fabric-1.20.1:remapJar
./gradlew --no-daemon :platform:fabric:fabric-1.21.1:remapJar
./gradlew --no-daemon :platform:sponge:sponge-1.20.1:shadowJar
./gradlew --no-daemon :platform:forge:forge-1.20.1:reobfShadowJar

# 聚合可发布 jar → build/dist/{bukkit,fabric,forge,neoforge,sponge}/
./gradlew --no-daemon :collectReleaseArtifacts
```

`./gradlew :buildAll` 是全量入口：构建全部子模块、执行发布产物结构门并聚合 `build/dist`（等价于全车道构建 + `:collectReleaseArtifacts`）。以下按车道展开的细项命令只在需要单车道制品时使用。

26.2 是第三期在制车道，根 wrapper 固定为 Gradle **9.6.1**，三条车道（Bukkit / Fabric / Forge）都是根构建子模块，均须以 **JDK 25 守护**运行根构建（26.2 两条 loom 车道在配置期硬校验守护 JVM；ADR-0025 / ADR-0026）：

```bash
# 26.2 三车道统一构建门（Paper/Fabric/Forge 产物）
./gradlew --no-daemon :buildRealServerArtifacts262

# 或单独构建 Fabric 26.2 / Forge 26.2 车道
./gradlew --no-daemon :platform:fabric:fabric-26.2:build
./gradlew --no-daemon :platform:forge:forge-26.2:packageArtifacts
```

NeoForge 1.20.2 已随 ADR-0025 迁移至 `top.wcpe.loom`（arch-loom 的 neoForge 配置，运行期 Mojmap 恒等 remap），并随 ADR-0026 成为根构建子模块（Gradle 统一 9.6.1）；核心经项目依赖直接消费，不再有"根先准备受控内部 JAR 再校验文件"的中间层：

```bash
# 在仓库根构建 NeoForge 车道
./gradlew --no-daemon :platform:neoforge:neoforge-1.20.2:packageArtifacts
```

Forge 跨代（目录在 `platform/forge/`；全部车道为根构建子模块，Gradle 统一 9.6.1，构建插件统一 `top.wcpe.loom` —— 1.12.2 走 legacy 链路、26.2 走无混淆 no-remap 链路；构建守护 JVM 须 ≥25）：

| MC | 目录 | 工程路径 / JDK |
|---|---|---|
| 1.20.1 | `platform/forge/1.20.1/` | `:platform:forge:forge-1.20.1`：根 Gradle 9.6.1（守护 JVM ≥25）构建，任务 `./gradlew :platform:forge:forge-1.20.1:reobfShadowJar`；编译工具链 Java 17 |
| 1.21.1 | `platform/forge/1.21.1/` | `:platform:forge:forge-1.21.1`：同一根 Gradle 9.6.1（守护 JVM ≥25）；编译工具链 Java 21 |
| 1.12.2 | `platform/forge/1.12.2/` | `:platform:forge:forge-1.12.2`：同一根 Gradle 9.6.1（守护 JVM ≥25）；编译工具链 Java 8（**client-only**，任务 `./gradlew :platform:forge:forge-1.12.2:prepareClientCompanionArtifacts`） |
| 26.2 | `platform/forge/26.2/` | `:platform:forge:forge-26.2`：同一根 Gradle 9.6.1（守护 JVM 必须 ≥25），`top.wcpe.loom-no-remap` + `loom.platform=forge` 无混淆管线（ADR-0025） |

### A 车道（真实 Paper / Folia smoke）

桩与 `mcTestkit { }` 接线都在 `:e2e:harness`（根构建子模块，ADR-0026：无自有 wrapper / `settings.gradle.kts`），机器人在 `e2e/bot`（根 `gradle.properties` 的 `mcTestkit.botDir=e2e/bot` 已给默认值）。被测插件由接线自动取 `:platform:bukkit:1.20.1:shadowJar` 产物并注入，**不需要**手工导出 jar 路径、也不需要再传 `-PmcTestkit.botDir`：

```bash
./gradlew --no-daemon :runMcTestkitSmoke        # Paper 1.20.1 smoke
./gradlew --no-daemon :runMcTestkitFoliaSmoke   # Folia 1.20.1 smoke（场景 smoke-folia）
```

## 2. 平台 API 模块

| 工程名 | 物理路径 | 用途 |
|---|---|---|
| `:platform:bukkit:bukkit-api` | `platform/bukkit/bukkit-api` | Bukkit 家族对外契约 |
| `:platform:fabric:fabric-api` | `platform/fabric/fabric-api` | Fabric 对外契约 |
| `:platform:forge:forge-api` | `platform/forge/forge-api` | Forge 对外契约 |
| `:platform:neoforge:neoforge-api` | `platform/neoforge/neoforge-api` | NeoForge 对外契约 |
| `:platform:sponge:sponge-api` | `platform/sponge/sponge-api` | Sponge 对外契约 |

玩法扩展 / 跨版本 common 应依赖 **api**，不要依赖版本实现 jar。

## 3. 版本矩阵 / 真服

```bash
./gradlew :listRealServerLanes
./gradlew :verifyVersionMatrixBuild
# 版本矩阵门（FR-12）：不含 NeoForge / Sponge / 26.2
./gradlew :runVersionMatrixGate
```

> **FR-12 已交付@v0.2.0**：R1–R6 合规矩阵 v2 + 用户第二期实机确认已齐；`:runVersionMatrixGate` 仍是 P2 报告门入口（只读权威报告，不代替真服实跑），**不能验证 26.2 / REALSERVER262**。

```bash
# 全 lane（含 NeoForge / Sponge 与 26.2）另用：
./gradlew :runRealServerAcceptance
# Folia SCHEDULER 报告路径：-P mpmt.acceptance.matrix=SCHEDULER 时读 server-report-scheduler.txt
./gradlew :runRealServerAcceptanceFolia -P mpmt.acceptance.matrix=SCHEDULER
```

请使用 **绝对路径** `:task`，避免无 `:` 时匹配到子工程同名 `runRealServerAcceptance`。

P3 / 26.2 REALSERVER262 三车道（原 `:runP3R7Gate` 与矩阵值 `R7` 已更名）：

```bash
# 构建门：Paper 26.2、Fabric 26.2 与 Forge 26.2 的产物（全部为根子模块任务）
./gradlew :buildRealServerArtifacts262

# 三车道以同一轮标识写出 REALSERVER262 权威报告后，才运行报告聚合门
./gradlew :runRealServerAcceptance262 \
  -P mpmt.acceptance.matrix=REALSERVER262 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒> \
  -P mpmt.acceptance.forge.serverRuntime=<本轮实际-Forge-服务端-JAR-绝对路径>
./gradlew :runRealServerGate262 \
  -P mpmt.acceptance.matrix=REALSERVER262 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒> \
  -P mpmt.acceptance.forge.serverRuntime=<本轮实际-Forge-服务端-JAR-绝对路径>

# Forge 26.2 真服的独立操作说明（仍在仓库根执行根任务）
./gradlew --no-daemon :platform:forge:forge-26.2:printRealServerAcceptanceRecipe
```

REALSERVER262 必须为 Paper、Fabric、Forge 三车道各提供一份属于**同一轮**的 `SERVER-GAMETEST-REPORT v2`，含 `MATRIX REALSERVER262`、`RUN_ID`、本轮开始毫秒、五个制品 role 的实际 SHA-256、`product-handshake` / `product-roundtrip` / `client-hud` 各一次 PASS、匹配的 `TOTAL` 与唯一末行 `RESULT PASS`。根门会拒绝旧报告、重复记录、额外失败/错误场景与制品漂移；依 ADR-0023，P3 / FR-16 的该严格门即为最终自动化验收。

Paper 26.2 宿主 + Fabric 26.2 客户端伴侣（REALSERVER262）：

```bash
# 终端 A：先起 Paper；两个占位值必须原样传给终端 B。
./gradlew :platform:bukkit:26.2:ensurePaperRealServerHost \
  -P mpmt.realserver.autoHost=true \
  -P mpmt.realserver.waitForReport=true \
  -P mpmt.acceptance.matrix=REALSERVER262 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒>

# 终端 B：确认 25599 已监听后运行 Fabric 26.2 客户端伴侣（根子模块任务）。
./gradlew :platform:fabric:fabric-26.2:runAcceptanceClient \
  -P mpmt.acceptance.server=127.0.0.1:25599 \
  -P mpmt.acceptance.matrix=REALSERVER262 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒>
```

P3 Paper 自动宿主只下载 `https://fill-data.papermc.io` 的冻结 build 71，缓存与新下载均核对 `paper-26.2-71.jar` 的 61,744,713 字节和 SHA-256 `36fee4f3a7020eb2e2d6f8d70d849beaf0f024d86f09302b9ccf2d96f266127e`；不跟随 `latest`。其他历史 Bukkit 自动宿主未声明冻结值时保持既有下载行为。该组合会由 Paper 车道产出当前 REALSERVER262 报告；Fabric 与 Forge 各自的服务端车道仍须另行产出相同轮次的报告，才能运行根聚合门。

Forge 1.21.1 专用服（车道已是根子模块，无车道自有 wrapper / launcher，命令仍在仓库根执行）：

```bash
./gradlew --no-daemon :platform:forge:forge-1.21.1:printRealServerAcceptanceRecipe
# 起服 + 客户端伴侣（:platform:forge:forge-1.21.1:runAcceptanceClient）+ ./gradlew :platform:forge:forge-1.21.1:verifyAcceptanceReport
```

Forge 1.12.2：**禁止** Forge 服务端 mod；真服走 CatServer HYBRID：

```bash
./gradlew :runRealServerAcceptanceCatServer
# 客户端伴侣：./gradlew :platform:forge:forge-1.12.2:prepareClientCompanionArtifacts
```

## 4. 脚手架换名

零 JDK 依赖，不需先跑通 Gradle 配置：

```bash
./init.sh                # 交互式
./init.sh --dry-run --id mygame --group com.example.mygame --name MyGame   # 预览
./init.sh --id mygame --group com.example.mygame --name MyGame             # 写盘
```

见 [`../tools/README.md`](../tools/README.md)。无需 python，搬迁 `java/` 与 `kotlin/` 下的源码包目录（含 `build-logic`）。

## 5. GitHub Actions

`ci.yml` 会在 pull request、`main`/`dev` 推送和手动触发时，在 Ubuntu Hosted Runner 固定构建 `mc-testkit` `v0.9.3` 到 Maven Local（远端 WCPE Releases 已发布同版，本地发布只作离线回退）；随后以 **JDK 25 守护**执行单一根构建的 `:buildAll`（Java 8 / 17 / 21 / 25 均显式安装，8/17/21 供 toolchain 解析），最后在根构建内构建 E2E harness（`:e2e:harness:build`）并校验 bot 模板。成功的默认分支运行保存 `build/dist`，任意结果均保存测试与静态分析诊断。

`release.yml` 只可由维护者手动触发，并且输入必须是已经推送、与 `VERSION` 一致的 `vX.Y.Z` 附注 tag。它会冷构建 13 个产品 jar 后创建 GitHub Release；不会创建 tag、不会发布 Maven 制品。需要强制人工审批时，在仓库设置中创建 `release` Environment 并添加保护规则。

CI 只验证可复现构建与静态质量，不能运行或替代 `:runRealServerGate262`、`runRealServerAcceptance` 等本机真服门。P3 26.2 真服门的权威仍是 ADR-0023 所定义的同轮 Gradle 报告。
