# ADR-0007：用 Gradle 复合构建隔离各加载器工具链，Bukkit 家族按系列收敛

## 状态
已接受

## 背景
取代 [ADR-0005](0005-build-toolchain.md)。ADR-0005 设想各平台模块在**同一构建内**各自应用其工具链。但这对模组加载器不成立：Fabric(Loom)、Forge(ForgeGradle)、NeoForge(NeoGradle)、Sponge(SpongeGradle) 各自的 Gradle 插件都重度接管构建（MC 依赖解析、重映射、运行配置），同处一个构建会相互冲突——类路径打架、所需 Gradle / JDK 版本不一、`pluginManagement` 互相干扰。AllinCore 用 `include` 组织多模块（但其为单加载器，未触此冲突）。本项目需多加载器并存，须把 AllinCore 的"模块化引入"思路扩展为**外置独立构建的复合引入**，让每个加载器插件各居其屋。

另需明确 Bukkit/Spigot/Paper/Folia 同属一个继承系列（Folia 是 Paper 的分叉，Paper 是 Spigot/Bukkit 的分叉）该如何收敛，避免按平台名无脑拆模块。

## 决策
1. **共享核心 L0–L2**（core-domain / core-runtime / core-server / core-client、protocol、platform-spi）为**根构建内的常规 `java-library` 模块**（Java 8）。
2. **每个带专属 Gradle 插件的平台——Fabric(Loom)、Forge(ForgeGradle)、NeoForge(NeoGradle)、Sponge(SpongeGradle)——各是一个自包含的独立 Gradle 构建**（各自 `settings.gradle.kts`、只应用自己的 loader 插件、设自己的 JDK），通常由根 `settings.gradle.kts` 经 **`includeBuild`** 复合引入并经依赖替换消费 `top.wcpe.mc.mpmt:core-*`。例外是 **NeoForge 1.20.2**：其使用 Gradle 8.14.5 自有 wrapper、消费根预构建的受控内部 JAR；根 Gradle 9 不得反向引入或嵌套执行，只校验该车道已生成的产物和报告。
3. **Bukkit 家族（Bukkit/Spigot/Paper/Folia）按一个系列收敛为单个插件构建 `platform-bukkit`**（普通 Java + shadow，无专属冲突插件，作根构建常规模块）：编译针对 **Bukkit 基线**，Paper/Folia 增强 API 用 `compileOnly`；**Paper/Folia 差异（尤以 Folia 区域调度 `RegionScheduler` vs 全局主线程为关键）运行期经 `FeatureGate` 探测并适配**，产出单个 jar 在 Bukkit/Spigot/Paper/Folia 通用（`plugin.yml` 声明 `folia-supported: true`）。不为 Folia/Paper 各拆独立构建/模块。
4. （承接 ADR-0005 仍然有效的核心）仍是自定义 Gradle、**不引入 Architectury**（其不覆盖 Bukkit/Sponge）。

## 理由
- 复合构建（`includeBuild`）是隔离相互冲突插件的规范手段；NeoForge 1.20.2 的自有 wrapper + 受控 JAR 则在根 Gradle 9 与 NeoGradle 工具链不兼容时保留物理隔离，二者都避免把 loader 插件塞进根构建。
- Bukkit 家族本是继承谱系，运行期 FeatureGate 收敛符合"特判经能力探测、不散落分支"的不变量（见 ADR-0002 §FeatureGate、ADR-0003），避免模块爆炸与适配器重复。
- 新增加载器 = 增加一条独立车道，通常加一个 `includeBuild`；若出现类似 NeoForge 1.20.2 的工具链冲突，则明确建立自有 wrapper 与根侧只读校验；新增 Bukkit 家族能力 = 在单一构建内加 FeatureGate 分支。

## 后果
- 正面：各 loader 插件零冲突；加载器可独立演进；Bukkit 家族一个 jar 跑全系列。
- 负面：复合构建或独立 wrapper 的配置与 IDE 导入略复杂；依赖替换与受控 JAR 输入都需统一坐标 / 文件名；**单个加载器内支持多 MC 版本**仍需在该构建内用版本适配子结构（源集 / 子模块，必要时再分构建），待 P2 落地 1.21.1 / 1.12.2 时细化（见 ADR-0003）。
- 约束（写入 architecture-invariants）：带专属插件的平台一律独立车道；**禁止把 Loom/ForgeGradle/NeoGradle/SpongeGradle 塞进同一构建；NeoForge 1.20.2 禁止反向 include 根构建或在根任务中嵌套其 wrapper**；**Folia 不得另起独立构建/模块，只走 FeatureGate**。
- 约束（评审）：core-* 如何进各 loader 产物（shade / JiJ / 不被 remap）见 [ADR-0012](0012-packaging-and-dependency-isolation.md)，且 M0 先做最小打包 spike，必要时回退"core 发 mavenLocal + 各 loader shadow 消费"。
- 约束（评审）：**"Bukkit 家族单 jar 通用"只在同一 MC 大版本族成立**；跨到 1.12.2（CatServer）Bukkit API 有大量破坏性变化，需 Bukkit 平台的 **L4**（version-api：通道注册 / 调度 / 聊天组件等），而非"P2 含糊细化"。

## 备选方案
- **全平台扁平多模块同构建**（ADR-0005 原设想）：加载器插件冲突——否决。
- **Architectury**：不覆盖 Bukkit/Sponge——否决（同 ADR-0005）。
- **Folia 独立构建模块**：与 Paper 大量重复、违背系列收敛与 FeatureGate 原则——否决（已与用户确认采用单构建 + FeatureGate）。
