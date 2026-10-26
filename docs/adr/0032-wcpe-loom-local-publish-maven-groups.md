# ADR-0032：本机 Maven 兜底放开 wcpe-loom 的发布坐标组

## 状态

已接受（修订 [ADR-0029](0029-contract-tests-assert-artifacts-not-source-text.md) 后果节里「mavenLocal 只放行两个组 / 允许组必须限于 `top.wcpe.*`」这一条护栏；其余决策不变）

## 背景

[ADR-0029](0029-contract-tests-assert-artifacts-not-source-text.md) 把 `mavenLocal` 的放行清单提成 `gradle.properties` 的 `mpmt.localMavenGroups`，并守两道约束：

1. 清单值必须**恰好**是 `top.wcpe.mc-testkit` / `top.wcpe.mc`；
2. 每个已放行的组必须以 `top.wcpe.` 开头。

这两条在当时是准确的：本机 m2 只需要兜底两个 wcpe 制品。但它们同时也假定「本机不需要发布构建插件本体」。

2026-10-21 排查 forge 1.12.2 dev `runClient` 崩溃时，这个假定被打破。根因是 wcpe-loom 的 `MinecraftLegacyPatchedProvider.patchForge()` 只删 `binpatches` 与 `META-INF` 签名，**从未把 Forge universal jar 从 SRG 名重映射到 named 名**；而运行期 `CoreModManager.handleLaunch` 会因 loom 提供的 MC jar 类名是 MCP 名而判定 `deobfuscatedEnvironment = true`，进而让 `DeobfuscationTransformer.shouldTransform` 对 `net.minecraft.*` / `net.minecraftforge.*` 全部豁免（`EXEMPT_DEV`），运行期不再做任何反混淆。于是 `extends fd` 无人翻译，`IllegalAccessError` / AT 静默失效 / `defineClass(null)` NPE 三症并发。

修复必须落在 loom 本身（新增 `remapForgeFromSrgToNamed`）。验证它需要：把打好的 loom 发布到本机 m2，再让车道项目解析到该版本。

实测发现这条路被 ADR-0029 的清单挡住了：

- 插件 marker 坐标是 `top.wcpe.loom:top.wcpe.loom.gradle.plugin`；
- 该 marker 依赖的实现是 `dev.architectury:architectury-loom`（wcpe-loom 是 architectury-loom 的 fork，沿用其 group）。

**两者缺一不可**：只放行 `top.wcpe.loom` 能解析到 marker，但 marker 拉不到实现，Gradle 静默回落到缓存里的旧版本——表现为「声明了新版本却仍加载旧 jar」。实测 `1.17.12` 与 `1.17.12-local.log4jfix` 两个坐标的 jar md5 完全相同，而日志回显的是 jar 内 `MANIFEST.MF` 的 `Implementation-Version` 而非坐标版本，进一步掩盖了这一点。

用 `-Pmpmt.localMavenGroups=...` 传参无法解决：`pluginManagement` 在 Gradle 求值 `-P` 属性之前执行。

## 决策

`mpmt.localMavenGroups` 的允许清单扩为八项：

```properties
mpmt.localMavenGroups=top.wcpe.mc-testkit,top.wcpe.mc,top.wcpe.loom,top.wcpe.loom-companion,top.wcpe.loom-no-remap,top.wcpe.loom-remap,top.wcpe.loom-repositories,dev.architectury
```

其中 wcpe-loom 相关的是五项 marker 组（`top.wcpe.loom`、`-companion`、`-no-remap`、`-remap`、`-repositories`）加一项实现组（`dev.architectury`）。Gradle 的 `includeGroup` 是**精确匹配**，`top.wcpe.loom` 不会覆盖 `top.wcpe.loom-no-remap`，因此必须逐个列出——实测漏掉 `-no-remap` 时，`26.2` 两条车道会因 marker 解析失败直接中断配置。

同时把 [ADR-0029](0029-contract-tests-assert-artifacts-not-source-text.md) 后果节的两道约束**改为白名单口径**：

- 清单值仍由 `RealServerGateContractTest` 结构化断言为上述八项的精确集合（放宽即红）；
- 命名空间约束从「必须全部是 `top.wcpe.*`」改为「`top.wcpe.*` **或**已登记的第三方例外」，`dev.architectury` 是**唯一**登记在案的例外。

新增任何其它第三方组，必须同时改该断言与本文档，不允许静默放宽。

## 理由

- **`dev.architectury` 是 wcpe-loom 自身的发布坐标**，不是任意第三方。放行它引入的制品面等价于放行 loom 本体——而 loom 本来就是这个构建的插件，信任边界没有实质外移。
- **保持精确集合断言**：仍然能拦住「有人往清单里多塞一个组」这类放宽。用「恰好四项」而非「包含四项」，鉴别力不降。
- **保留命名空间约束并显式登记例外**：比直接删掉该断言好——它仍禁止任何未登记的第三方组混入，只是承认了一个已论证的例外。

## 后果

正面：

- 本机发布 wcpe-loom 新版本后，车道项目能真正解析并验证它。这是 1.12.2 修复可验证的前提。
- 放行面仍受两个结构化断言约束（精确集合 + 命名空间白名单），不会静默失控。

负面与约束：

- **`dev.architectury` 下的一切制品都被本机 m2 兜底放行**。这是真实的作用域扩大：若本机 m2 里存在同 group 的其它制品，它们也进入了解析候选。缓解：`mavenLocal` 在仓库列表中并非首位，仅在其它仓库缺失该坐标时才命中；且 `dev.architectury` 下本机通常只发布 architectury-loom 自身。
- 契约测试的失败文案与断言结构有变化（`RealServerGateContractTest` 的用例名与允许集合），这是 [ADR-0029](0029-contract-tests-assert-artifacts-not-source-text.md) 意义上的护栏变更，需在该 ADR 的后果节留痕——本文档已取代其对应段落。
- 本决策只解决「能否解析到新 loom」。它不保证 1.12.2 dev `runClient` 一定跑通；后者取决于 `remapForgeFromSrgToNamed` 的重映射结果是否正确。

## 备选方案

- **只放行 `top.wcpe.loom`**：不可行。marker 依赖 `dev.architectury:architectury-loom`，缺实现则静默回落旧版本（已实测）。
- **把 loom 发布到 `repo.wcpe.top` 等远端仓库**：更干净，不触碰本机护栏。但需要网络与发布凭据，本地验证阶段无法自助完成；可作为后续正式发布通道。
- **保持清单不变，改用 `--include-build` 复合构建直接引入 loom 源码**：绕开 m2 解析，但要改 `settings.gradle.kts` 引入独立工程，影响面大于本决策，且与 ADR-0026 的构建拓扑决策冲突。
- **放弃验证，把 1.12.2 修复登记为未验证状态**：零风险，但 1.12.2 dev `runClient` 长期不可用，且已定位的根因无法被证实。
