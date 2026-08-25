# 运维手册：MultiPlatformModTemplate

> 物理布局：`core/` · `platform/<loader>/{api,版本}/` · `modules/`。  
> Gradle 工程名保持稳定；`-p` 与 `projectDir` 指向收纳后的路径。

## 1. 构建

> 当前上游 `mc-testkit` plugin marker 不可用时，根 `settings.gradle.kts` 会优先从本机 Maven 仓库解析 `top.wcpe.mc-testkit:0.5.1` 及其 `top.wcpe.mc` 实现模块；该回退只限这两个 group。线上仓库恢复后仍应保持本地制品与发布坐标一致。

```bash
# 根 L0–L2 + 平台 api
./gradlew --no-daemon :core:domain:build :core:spi:build
./gradlew --no-daemon :platform:bukkit:bukkit-api:build :platform:fabric:fabric-api:build

# Bukkit 版本产物
./gradlew --no-daemon :platform:bukkit:1.20.1:shadowJar
./gradlew --no-daemon :platform:bukkit:1.12.2:shadowJar
./gradlew --no-daemon :platform:bukkit:1.21.1:shadowJar
./gradlew --no-daemon :platform:bukkit:26.2:shadowJar # Java 25

# Fabric / Forge / Sponge（-p 用物理路径）
./gradlew -p platform/fabric/1.20.1 --no-daemon remapJar
./gradlew -p platform/fabric/1.21.1 --no-daemon remapJar
./gradlew -p platform/sponge/1.20.1 --no-daemon shadowJar

# 聚合可发布 jar → build/dist/{bukkit,fabric,forge,neoforge,sponge}/
./gradlew --no-daemon :collectReleaseArtifacts
```

26.2 是第三期在制车道，根 wrapper 已固定为 Gradle **9.6.1**，三个车道均需要 Java 25：

```bash
# 根先准备受控内部 JAR，再委托 Fabric 26.2 includeBuild
./gradlew --no-daemon :buildFabric262

# Forge 26.2 必须在物理目录使用自有 wrapper；根构建不得嵌套调用它
./platform/forge/26.2/gradlew --no-daemon packageArtifacts
```

NeoForge 1.20.2 是独立 Gradle 8.14.5 车道；根 Gradle 9 只准备受控内部 JAR 并校验已生成的产物 / 报告，既不能用 `-p` 形式调用，也不会嵌套执行其 wrapper：

```bash
# 在仓库根准备 NeoForge 受控内部 JAR
./gradlew --no-daemon :prepareNeoForge1202Inputs

cd platform/neoforge/1.20.2
./gradlew --no-daemon packageArtifacts
cd ../../..
```

Forge 跨代（自有 launcher，目录在 `platform/forge/`，禁止从根嵌套 gradlew）：

| MC | 目录 | JDK / Gradle |
|---|---|---|
| 1.20.1 | `platform/forge/1.20.1/` | Gradle 运行于 Java 21、编译工具链为 Java 17 + Gradle 8.14.5（先将该版本置于 `PATH`，运行 `gradle --no-daemon reobfShadowJar`） |
| 1.21.1 | `platform/forge/1.21.1/` | Java 21 + 8.12.1 |
| 1.12.2 | `platform/forge/1.12.2/` | Java 8 + 5.6.4（**client-only**，运行 `./gradlew --no-daemon prepareClientCompanionArtifacts`） |
| 26.2 | `platform/forge/26.2/` | Java 25 + 9.6.1 + ForgeGradle 7.0.31 |

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
# P2 核心矩阵门（FR-12）：不含 NeoForge / Sponge / 26.2；等价别名 :runP2StrictCheck
./gradlew :runVersionMatrixGate
```

> **FR-12 已交付@v0.2.0**：R1–R6 合规矩阵 v2 + 用户第二期实机确认已齐；`:runVersionMatrixGate` 仍是 P2 报告门入口（只读权威报告，不代替真服实跑），**不能验证 26.2/R7**。

```bash
# 全 lane（含 NeoForge / Sponge 与 26.2）另用：
./gradlew :runRealServerAcceptance
# Folia R6 报告路径：-P mpmt.acceptance.matrix=R6 时读 server-report-r6.txt
./gradlew :runRealServerAcceptanceFolia -P mpmt.acceptance.matrix=R6
```

请使用 **绝对路径** `:task`，避免无 `:` 时匹配到子工程同名 `runRealServerAcceptance`。

P3 / 26.2 R7 三车道：

```bash
# 构建门：Paper 26.2、Fabric 26.2，以及已由 Forge 自有 wrapper 生成的双 JAR
./gradlew :runP3R7Build

# 三车道以同一轮标识写出 R7 权威报告后，才运行报告聚合门
./gradlew :runP3R7RealServerAcceptance \
  -P mpmt.acceptance.matrix=R7 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒> \
  -P mpmt.acceptance.forge.serverRuntime=<本轮实际-Forge-服务端-JAR-绝对路径>
./gradlew :runP3R7Gate \
  -P mpmt.acceptance.matrix=R7 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒> \
  -P mpmt.acceptance.forge.serverRuntime=<本轮实际-Forge-服务端-JAR-绝对路径>

# Forge 真服的独立操作说明（在 platform/forge/26.2 下执行）
./platform/forge/26.2/gradlew --no-daemon printRealServerAcceptanceRecipe
```

R7 必须为 Paper、Fabric、Forge 三车道各提供一份属于**同一轮**的 `SERVER-GAMETEST-REPORT v2`，含 `MATRIX R7`、`RUN_ID`、本轮开始毫秒、五个制品 role 的实际 SHA-256、`product-handshake` / `product-roundtrip` / `client-hud` 各一次 PASS、匹配的 `TOTAL` 与唯一末行 `RESULT PASS`。根门会拒绝旧报告、重复记录、额外失败/错误场景与制品漂移；依 ADR-0023，P3 / FR-16 的该严格门即为最终自动化验收。

Paper 26.2 宿主 + Fabric 26.2 客户端伴侣（R7）：

```bash
# 终端 A：先起 Paper；两个占位值必须原样传给终端 B。
./gradlew :platform:bukkit:26.2:ensurePaperRealServerHost \
  -P mpmt.realserver.autoHost=true \
  -P mpmt.realserver.waitForReport=true \
  -P mpmt.acceptance.matrix=R7 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒>

# 终端 B：确认 25599 已监听后运行 Fabric 客户端伴侣。
./gradlew -p platform/fabric/26.2 runAcceptanceClient \
  -P mpmt.acceptance.server=127.0.0.1:25599 \
  -P mpmt.acceptance.matrix=R7 \
  -P mpmt.acceptance.runId=<同一轮-run-id> \
  -P mpmt.acceptance.startEpochMs=<同一轮-开始毫秒>
```

P3 Paper 自动宿主只下载 `https://fill-data.papermc.io` 的冻结 build 71，缓存与新下载均核对 `paper-26.2-71.jar` 的 61,744,713 字节和 SHA-256 `36fee4f3a7020eb2e2d6f8d70d849beaf0f024d86f09302b9ccf2d96f266127e`；不跟随 `latest`。其他历史 Bukkit 自动宿主未声明冻结值时保持既有下载行为。该组合会由 Paper 车道产出当前 R7 报告；Fabric 与 Forge 各自的服务端车道仍须另行产出相同轮次的报告，才能运行根聚合门。

Forge 1.21.1 专用服（独立 launcher）：

```bash
cd platform/forge/1.21.1
./gradlew --no-daemon printRealServerAcceptanceRecipe
# 起服 + 客户端伴侣 + ./gradlew verifyAcceptanceReport
```

Forge 1.12.2：**禁止** Forge 服务端 mod；真服走 CatServer R5：

```bash
./gradlew :runRealServerAcceptanceCatServer
# 客户端伴侣：./platform/forge/1.12.2/gradlew --no-daemon prepareClientCompanionArtifacts
```

## 4. 脚手架换名

```bash
./gradlew \
  -P mpmt.scaffold.id=mygame \
  -P mpmt.scaffold.group=com.example.mygame \
  -P mpmt.scaffold.name=MyGame \
  -P mpmt.scaffold.dryRun=true \
  renameScaffold
```

见 [`../tools/README.md`](../tools/README.md)。纯 kts，无需 python。

## 5. GitHub Actions

`ci.yml` 会在 pull request、`main`/`dev` 推送和手动触发时，在 Ubuntu Hosted Runner 固定构建 `mc-testkit` `v0.5.1` 到 Maven Local；Forge 1.20.1 使用固定 Gradle 8.14.5，其余独立 Forge/NeoForge 车道按 Java 8 / 17 / 21 / 25 使用自有 wrapper，最后执行根 `:buildAll`。成功的默认分支运行保存 `build/dist`，任意结果均保存测试与静态分析诊断。

`release.yml` 只可由维护者手动触发，并且输入必须是已经推送、与 `VERSION` 一致的 `vX.Y.Z` 附注 tag。它会冷构建 13 个产品 jar 后创建 GitHub Release；不会创建 tag、不会发布 Maven 制品。需要强制人工审批时，在仓库设置中创建 `release` Environment 并添加保护规则。

CI 只验证可复现构建与静态质量，不能运行或替代 `:runP3R7Gate`、`runRealServerAcceptance` 等本机真服门。P3 R7 的权威仍是 ADR-0023 所定义的同轮 Gradle 报告。
