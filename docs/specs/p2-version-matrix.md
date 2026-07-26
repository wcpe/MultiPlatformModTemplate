# 功能规格：P2 版本矩阵与工具链隔离

> 状态：已交付@v0.2.0　·　关联 PRD：FR-12、FR-25　·　分支：feature/p2-version-matrix　·　架构决策：[ADR-0021](../adr/0021-p2-version-matrix-toolchain-isolation.md)

## 1. 背景与目标

P2 沿版本轴把已在 1.20.1 证明的 L4、跨端协议与 realserver 验收扩展到 **1.21.1 / 1.12.2**，并完成 CatServer 1.12.2 实跑。本规格是 P2 冻结矩阵、版本、工具链、线缆向量、报告契约和严格门的**唯一权威位置**。

### 1.1 实施前提

本轮从基线 `c5ae73f` 开始，在分支 `feature/p2-version-matrix` 的唯一 P2 工作树实施；原脏工作区中的未提交内容不得带入。该约束仅是本轮实施前提，不是产品长期不变量。

### 1.2 目标

1. 证明 1.21.1 与 1.12.2 都能经 L4 复用既有 L0–L2 与产品协议 payload。
2. 用确定的 launcher / Gradle / ForgeGradle / JDK 映射隔离三条 Forge 构建车道。
3. 用唯一脚本入口串行验证 P2 核心矩阵与受影响的 1.20.1 基线。
4. 用 golden vectors 和 `SERVER-GAMETEST-REPORT v2` 防止“控制通道绿但产品链路未验证”与旧报告误放行。

## 2. 有效矩阵与范围

P2 有效矩阵**不是笛卡尔积**：

| 维度 | 服务端产品 | 客户端产品 / 伴侣 | 说明 |
|---|---|---|---|
| MC 1.21.1 | Paper 插件、Fabric 服务端 mod、Forge 服务端 mod | Fabric / Forge 客户端产品 mod 与验收伴侣 | 仅 Paper / Fabric / Forge；Java 21 |
| MC 1.12.2 | Bukkit 产品插件 + Bukkit 验收插件，部署到 CatServer | Forge 客户端产品 mod + Forge 验收伴侣 | 唯一活跃平台 `bukkit`；Java 8 |
| Folia 1.20.1 | 共享 Bukkit 产品插件 + Bukkit 验收插件 | Fabric 1.20.1 验收客户端 | 固定 build 17；Java 17；共享 Bukkit 强制回归门 |
| 受影响的 1.20.1 基线 | 既有 Bukkit / Fabric / Forge 产物 | 既有 Fabric / Forge 客户端 | 只做根及 Bukkit/Fabric/Forge 构建回归 |

硬约束：

- 1.21.1 只覆盖 Paper / Fabric / Forge。
- 1.12.2 只覆盖 Bukkit 产品/验收插件、Forge 客户端产品/验收伴侣及 CatServer realserver。
- CatServer 服务端绝不安装我方 Forge 服务端 mod；我方唯一活跃平台必须为 `bukkit`，`HYBRID_FORGE_BUKKIT=true`。
- 截至 2026-07-18，Folia 官方无 1.21.1 构建；不得建立或宣称 Folia 1.21.1 格子。
- Sponge、NeoForge、26.2 均不属于 P2，不进入 P2 聚合脚本依赖图，不阻断 FR-12，也不得据 P2 结果宣称全产品平台宇宙全部实机通过。

## 3. 冻结版本、制品与来源标识

核对日：**2026-07-18**。下表的来源标识用于追溯冻结依据，不在文档中保存裸 URL。

| 目标 | 冻结版本 / 制品 | SHA-256 | 来源标识 |
|---|---|---|---|
| Paper 1.21.1 | build 133 | `39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9` | Paper Downloads API：project/version/build |
| Fabric 1.21.1 | Loader `0.19.3`；Fabric API `0.116.14+1.21.1` | 依赖坐标锁定 | Fabric Maven / metadata |
| Forge 1.21.1 | `1.21.1-52.1.0` | 依赖坐标锁定 | Forge 官方下载索引 |
| CatServer 1.12.2 | release `25.02.04`；`CatServer-4168d848-universal.jar`；7,795,165 字节 | `eaf575310acbb48d535212cfb88d93de69f90f2a81879a26f88457713a25952e` | CatServer GitHub release `25.02.04` |
| Forge 1.12.2 | `1.12.2-14.23.5.2860` | 依赖坐标锁定 | Forge 官方下载索引 |
| Folia 1.20.1 | build 17 | `c533d8886c60e1db17ebcf841b862731ab0a18d72377f37189930c3324eb7759` | Folia Downloads API：project/version/build |

受控制品必须在启动前校验冻结哈希与大小；禁止跟随 latest。CatServer 已从固定 release 首次受控获取并冻结 SHA-256，R5 仍须在启动前按本表复核。

## 4. 构建车道与 JDK

### 4.1 Forge 唯一映射

| MC | launcher | Gradle | ForgeGradle | Forge | JDK |
|---|---|---|---|---|---|
| 1.20.1 | `./gradlew -p platform-forge-1.20.1` | 8.10.2 | 6.0.54 | `1.20.1-47.4.2` | Java 17 |
| 1.21.1 | `./platform-forge-1.21.1/gradlew`（自有 wrapper，不并入根） | 8.12.1 | 6.0.54 | `1.21.1-52.1.0` | Java 21 |
| 1.12.2 | `./platform-forge-1.12.2/gradlew` | 5.6.4 | 3.0.197 | `1.12.2-14.23.5.2860` | Java 8 |

每次配置只加载目标版本的 **唯一** ForgeGradle / userdev。禁止一次配置混多代 FG；禁止用根 `./gradlew -p platform-forge-1.20.1` 构建 1.21.1。1.12.2 不加入根 `includeBuild`，只产客户端产品/验收伴侣。

### 4.2 其它车道

- **废除** `-Pmpmt.minecraftVersion`：Bukkit 用 `:platform-bukkit:server-x.y.z`；Fabric 用 `platform-fabric-1.20.1` / `1.21.1` 独立 includeBuild。
- 1.21.1 产品编译与 realserver 使用 Java 21；1.12.2 使用 Java 8；Folia 1.20.1 使用 Java 17。
- 根 Gradle 8.10.2 需 Java 17+ 启动时，1.12.2 Bukkit **目标字节码仍固定 Java 8**。
- L0–L2 继续 `--release 8`。
- realserver 必须用目标车道显式 Java executable。
- 各版本车道与 realserver **串行**，禁止共享缓存/运行目录并发构建。

## 5. 通道与产品隔离

### 5.1 现代通道

- 产品：`mpmt:main`。
- 验收控制：`mpmt-test:acceptance`。

### 5.2 1.12.2 通道正式冻结

- 产品：`MPMT`，4 个 ASCII 字符。
- 验收控制：`MPMTTEST`，8 个 ASCII 字符。
- 两者均满足 legacy Bukkit 的 1–16 ASCII 字符限制。
- 产品插件只在 enable / disable 注册、注销 `MPMT`。
- 验收插件独占 `MPMTTEST`；产品 jar 不得包含验收控制通道实现或 `MPMTTEST` 字面量。
- 通道包装可因平台/版本不同，但产品协议 payload 字节不得改变。
- 验收控制通道成功不能替代任何产品 required scenario。

## 6. 线缆 golden vectors

未来唯一向量文件冻结为：

`protocol/src/test/resources/golden/wire-v1.json`

生成与锁定规则：

1. 必须先从基线 `c5ae73f` 的 `PacketCodec` 行为生成。
2. 生成后人工审查并锁定；后续不得由当前实现静默覆盖期望值。
3. 覆盖 `PacketCodec` 注册的 10 类包：`ClientHello`、`ServerHello`、`ServerMessage`、`ServerHudMessage`、`Disconnect`、`ClientIdReport`、`Ping`、`Pong`、`ResyncRequest`、`Fragment`。
4. 用例至少覆盖：空字符串、ASCII、中文 + emoji、允许上限 UTF；0、负值及各编码边界数值；boolean 双值；空分片、单块分片、协议允许的最大块分片及 CRC。
5. 1.12.2、1.20.1、1.21.1 各 L4 必须捕获产品通道内的**裸 payload**，与同一向量逐字节相等；只允许平台通道外层包装不同。
6. golden vector 不覆盖验收控制协议；产品 jar 与产品 payload 不得因验收实现变化。

## 7. 唯一 P2 聚合入口

**唯一入口是 Gradle 任务**（禁止 `scripts/*.sh` / ps1 编排）：

```bash
./gradlew verifyVersionMatrixBuild    # 构建矩阵（无真服）
./gradlew :runVersionMatrixGate     # P2 核心矩阵门（= runP2StrictCheck 别名）
./gradlew :runP2RealServerAcceptance # 仅 R1–R6 相关车道报告门
./gradlew :runRealServerAcceptance   # 全 lane（含 NeoForge/Sponge，非 P2 阻断）
```

编排：`build-logic/realserver-acceptance` + 根薄包装；辅车道 mc-testkit。禁止 `buildAll` 充当 P2 门。实现必须：

- 矩阵启用时校验 `MPMT_JAVA8_HOME` / `MPMT_JAVA17_HOME` / `MPMT_JAVA21_HOME`。
- 每子构建显式 JDK，优先 `--no-daemon`；**禁止嵌套 `gradlew`**。
- 按 §7.1 串行；失败即停。
- P2 不聚合 Sponge / NeoForge / 26.2 作为阻断门（可另跑）。

### 7.1 逻辑子门精确定义

| 顺序 | 子门 | 精确范围 | launcher / JDK |
|---|---|---|---|
| 1 | `p2BaselineCheck` | 根 + Bukkit/Fabric/Forge **1.20.1** 构建；无 realserver | 见下方固定命令；Java 17 |
| 2 | `verifyVersionMatrixBuild` | Bukkit 1.12/1.20/1.21 + Fabric 1.20/1.21 + Forge 1.20 打包；打印 Forge 1.21/1.12 自有 launcher | 根 `./gradlew verifyVersionMatrixBuild`；Java 8/17/21 按目标 |
| 3 | `p2ArtifactCheck` | 冻结哈希、产物角色、1.12 通道隔离、wire golden | validator；不启 realserver |
| 4 | `p2R1FabricSameStack` | Fabric 1.21.1 ↔ Fabric 1.21.1 | `-p platform-fabric-1.21.1`；Java 21 |
| 5 | `p2R2ForgeSameStack` | Forge 1.21.1 ↔ Forge 1.21.1 | `./platform-forge-1.21.1/gradlew`；Java 21 |
| 6 | `p2R3FabricPaper` | Fabric 1.21.1 → Paper 1.21.1 | Fabric 1.21.1 + Paper；Java 21 |
| 7 | `p2R4ForgePaper` | Forge 1.21.1 → Paper 1.21.1 | Forge 1.21.1 + Paper；Java 21 |
| 8 | `p2R5CatServer` | Forge 1.12.2 客户端 → CatServer + Bukkit 1.12.2 插件 | `platform-forge-1.12.2` + `server-1.12.2`；Java 8 |
| 9 | `p2R6Folia` | Fabric 1.20.1 客户端 → Folia + Bukkit 1.20.1 插件 | Fabric 1.20.1 + `server-1.20.1`；Java 17 |
| 10 | `p2ReportCheck` | R1–R6 v2 报告聚合 | validator |

`p2BaselineCheck`（`MPMT_JAVA17_HOME`）：

```bash
./gradlew --no-daemon :core-domain:build :platform-spi:build
./gradlew --no-daemon :platform-bukkit:server-1.20.1:verifyPackaging
./gradlew -p platform-fabric-1.20.1 --no-daemon verifyPackaging
./gradlew -p platform-forge-1.20.1 --no-daemon verifyPackaging
```

`verifyVersionMatrixBuild`（根任务已实现主体；Forge 跨代仅打印）：

```bash
./gradlew --no-daemon verifyVersionMatrixBuild
# 另终端（自有 launcher，禁止嵌套）：
# ./platform-forge-1.21.1/gradlew --no-daemon build
# ./platform-forge-1.12.2/gradlew --no-daemon build
```

根 launcher 与目标编译工具链必须分开校验；Forge 三车道与 §4.1 逐项一致。

## 8. `SERVER-GAMETEST-REPORT v2` 契约

### 8.1 必需结构

每份报告首行固定为：

```text
SERVER-GAMETEST-REPORT v2
```

并至少包含以下唯一元数据与记录：

- `RUN_ID`：本次聚合脚本生成、传给服务端与 validator 的唯一值。
- `MATRIX`：`R1`–`R6` 对应的唯一矩阵键。
- `START_EPOCH_MS`：本轮子门启动时间。
- 服务端 Java major 与 executable。
- 客户端 Java major 与 executable。
- 每个受控制品一条 `ARTIFACT`：至少覆盖 `server-runtime`、`server-product`、`server-acceptance`、`client-product`、`client-acceptance`；存在独立客户端运行时制品时另记 `client-runtime`。每条含 role 与 SHA-256。
- 每个场景一条场景行，含 scenario id 与 PASS/FAIL/ERROR/SKIP。
- 唯一 `TOTAL` 汇总行。
- 末行唯一 `RESULT PASS|FAIL`。

### 8.2 required scenarios

R1–R6 公共 required scenario，均须**恰好一次 PASS**：

- `product-handshake`
- `product-roundtrip`
- `client-hud`

R5 另须恰好一次 PASS：

- `forge-client-optional`
- `active-platform-bukkit`
- `hybrid-forge-bukkit`
- `server-forge-product-absent`

R6 另须恰好一次 PASS：

- `global-scheduler`
- `region-scheduler`
- `entity-scheduler`

### 8.3 validator 拒绝条件

validator 必须接收预期 runId、matrix、启动时间、Java major/executable 与全部制品 role/hash，并拒绝：

- 旧版报告、旧文件或报告 mtime 早于本轮启动。
- 缺失或重复的必需元数据、制品 role、场景、`TOTAL`、`RESULT`。
- 任一 required scenario 为 SKIP/FAIL/ERROR，或并非恰好一次 PASS。
- runId、matrix、START_EPOCH_MS、JDK major/executable、制品 SHA-256 与预期不匹配。
- 超时、服务端/客户端进程异常退出、未清理残留进程，或报告末行不是 `RESULT PASS`。
- 只证明验收控制通道可用而缺少产品场景。

## 9. R5 兼容失败语义

Forge 1.12.2 客户端产品与验收伴侣必须声明并实证 **client-only / optional**：服务端没有我方 Forge mod 时仍允许加入，等价实现可使用 `acceptableRemoteVersions` / `NetworkCheck`。客户端只有在成功加入 CatServer 后才开始应用层产品握手。

R5 启动后必须同时证明：

1. 服务端 mod 列表不存在我方 Forge 产品。
2. 我方唯一活跃平台为 `bukkit`。
3. `HYBRID_FORGE_BUKKIT=true`。
4. Forge 客户端 optional 检查允许进入无我方 Forge mod 的服务端。
5. 加入后产品 `MPMT` 完成握手、往返和 HUD。

任一条件不满足，服务端立即写对应场景 FAIL 与 `RESULT FAIL`，停止并清理服务端/客户端进程和本轮临时目录；不得降级成控制通道 PASS。

## 10. 测试先行与实施顺序

所有行为与守卫先写失败测试，再实现到通过。顺序冻结为：

1. 规格审核。
2. 构建车道 / 守卫。
3. Fabric 1.21.1。
4. Forge 1.21.1。
5. Bukkit 1.21.1 / 1.12.2。
6. Forge legacy 1.12.2。
7. realserver 与 v2 报告。
8. `./gradlew runVersionMatrixGate` 严格门（Gradle，禁 sh）。
9. 文档同步。

## 11. 验收标准

- 有效矩阵与 §2 完全一致，不存在 1.12.2 Fabric、Folia 1.21.1 或 CatServer Forge 服务端产品格子。
- 冻结版本、来源标识、SHA 与 §3 一致；CatServer SHA 已在首次受控获取后冻结。
- Forge 三车道的 launcher/Gradle/FG/Forge/JDK 与 §4.1 唯一映射一致，一次配置只加载目标车道对应的 ForgeGradle 与 userdev。
- 1.12 通道与 jar 隔离满足 §5；产品 payload 与三版本 golden vectors 逐字节一致。
- `./gradlew runVersionMatrixGate` 是唯一聚合入口，不调用 `buildAll`，按 §7 串行、显式使用三个 Java home；**禁止** shell 剧本入口。
- R1–R6 各自产生通过 §8 validator 的 `SERVER-GAMETEST-REPORT v2`。
- R5 满足 §9 client-only/optional 与融合服不变量；R6 的三类调度场景各恰好一次 PASS。
- P2 结果只代表核心矩阵 + 受影响 1.20.1 基线，不包含 Sponge/NeoForge/26.2，也不宣称全平台宇宙实机通过。
- 实机维度由用户最终确认。


### 11.1 FR-12 交付门（硬约束）

在以下条件**全部**满足之前，**禁止**将 PRD FR-12 标为 `已交付@…`，也**禁止**以第二期名义发版收口：

1. R1–R4 各自产出合规 `SERVER-GAMETEST-REPORT v2`（含 `MATRIX`/`RUN_ID`/制品哈希 + 公共三场景恰好一次 PASS；不得以默认 P1 14 项报告冒充）。
2. R5 / R6 在当前 HEAD（含 `be68ac9` HUD 修复后）有可复核的合规矩阵报告，或明确记录可接受的历史证据与复跑计划。
3. `./gradlew :runVersionMatrixGate`（≡ `:runP2StrictCheck`）在本机对**当前 HEAD 权威报告**放行。
4. **用户对第二期 / P2 实机维度作出最终确认**（第一期确认不自动覆盖第二期）。

当前状态（对照 2026-07-26 发版 `v0.2.0`）：FR-12 = **已交付@v0.2.0**。R1–R6 合规矩阵 v2 真服 `RESULT PASS` 已归档于 `.tmp/matrix-r{1..6}-attempt/server-report-r{n}-PASS.txt`；`:runVersionMatrixGate` 放行；用户第二期 / P2 实机最终确认通过（`.tmp/acceptance-phase-P2-user-confirm-20260726.md`）。

## 12. 风险 / 待定

- CatServer 固定制品已于本规格核对日完成首次受控获取并冻结 SHA-256；R5 编排仍须校验哈希、大小与 release 标识，任一不符立即失败。
- 旧版 Gradle/FG/Java 8 与现代工具链必须物理隔离且串行执行。
- `wire-v1.json` 必须从基线生成后人工锁定，不能以新实现反向定义兼容基准。
- 本规格与 ADR-0021 已通过独立二次复核并接受；实现状态仍以任务清单与 PRD 状态为准。
