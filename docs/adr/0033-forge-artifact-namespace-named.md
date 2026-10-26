# ADR-0033：Forge 车道的产物命名空间统一到 named

## 状态

已弃用（实测证伪，未采纳）

> **证伪记录（2026-10-21）**：本 ADR 的核心假设——把重映射目标改为 named 即可让 MC 对 Forge 新增成员的引用被正确翻译——经实测不成立。
>
> 实验：对 `merged-at-patched.jar`（notch）直接做 `official → named` 重映射（TinyRemapper 0.14.0 + `mappings-srg-inherited.tiny`，程序见 wcpe-loom `.tmp/exp-remap/ExpRemap.java`），产物中 `ModelManager` 的调用仍为：
>
> ```
> invokevirtual net/minecraftforge/client/model/ModelLoader.a:()Lnet/minecraft/util/registry/IRegistry;
> ```
>
> 方法名完全没有被翻译。原因：`ModelLoader` 是 **Forge 类**，不在 MC 的映射表内；映射表中查不到 `ModelLoader.a`，因此**无论目标命名空间选什么都不会翻译**。本 ADR 的决策前提不成立。
>
> 正确方向见文末「证伪后的修正」。

## 背景

forge 1.12.2 dev `runClient` 在修掉 AT 静默失效与 srg 表问题后，仍卡在：

```
java.lang.NoSuchMethodError: net.minecraftforge.client.model.ModelLoader.a()Lnet/minecraft/util/registry/IRegistry;
  at net.minecraft.client.renderer.block.model.ModelManager.onResourceManagerReload(ModelManager.java:28)
  at net.minecraft.client.Minecraft.init(Minecraft.java:513)
```

用运行期实际加载的 jar（从 `--debug` 抓到的 `-cp` 确认是 `minecraftMaven` 下的 `generated-intermediate` 变体）核对字节码，得到：

```java
// ModelManager（MC 类）在该 jar 里
private IRegistry<...> modelRegistry;                            // 字段已是 named ✅
invokevirtual net/minecraftforge/client/model/ModelLoader.a:()L...IRegistry;  // 方法名仍是 notch ❌
```

而 Forge 侧同一方法经 `remapForgeToNamed` 后已叫 `setupModelRegistry`（javap 确认）。

原因不是"漏翻"，而是**同一份运行期 classpath 里混了两个命名空间**：MC 侧成员已进 named，而对 Forge 新增成员的引用停在了 notch。`ModelLoader.a` 在 MC 原始 jar 里并不存在（由 Forge 添加），MC 重映射时映射表查不到它，故无法翻译——这是「MC jar 单侧重映射」的固有边界。

进一步核对代码后确认：`MinecraftPatchedProvider.remapPatchedJar` 早已是"一次重映射覆盖 MC 与 Forge"——它把 `atPatchedJar`、`forgeJar`、`forgeUserdevJar` 三个输入喂进同一个 `TinyRemapper` 并依次 `apply`。因此**不存在"两次独立重映射"的流水线问题**，无需重排 `mergeJars` / `remapJar` 顺序。

真正的分歧点在映射方向：

| 位置 | 目标命名空间 |
|---|---|
| `MinecraftPatchedProvider.buildRemapper` | `"official"`（notch），硬编码 |
| `AbstractMappedMinecraftProvider`（loom 标准 MC 重映射） | `"named"` |
| 运行期 classpath 实际加载的制品 | named |

即：Forge 车道的 patched jar 被送去 official，而 loom 标准流程与运行期期望的都是 named。两者不一致，才让"对 Forge 新增成员的引用"无处可翻。

核实时还发现第三处不一致：`remapPatchedJar` 的源命名空间声明为 `srg`（`IntermediaryNamespaces.intermediary` 的 `case FORGE -> SRG`），但它的实际输入 `atPatchedJar` 是 notch 名（实测 `fd.class`、字段 `a`）。源声明与输入空间不符，会让映射查找整体错位。

## 决策

把 Forge 车道 patched jar 的重映射目标从硬编码 `"official"` 改为按平台取正确目标命名空间，legacy Forge 取 `named`，与 loom 标准流程和运行期制品保持一致。

连带处理：

1. **AT 翻译的目标命名空间同步**。`AccessTransformerRuleTranslator` 当前按 `official` 注入（`MinecraftPatchedProvider.createTranslatedAccessTransformerOptions` 与 `AccessTransformForgeJarTask.getAtTargetNamespace`），目标改为 named 后必须同步，否则 AT 会从"生效"退回"静默失效"。
2. **重新评估 `remapForgeToNamed` 是否仍必要**。若整体重映射目标已是 named，`patchForge` 阶段的单独 srg→named 可能变成多余步骤，需实测确认后再决定保留或移除。
3. **核对 `remapPatchedJar` 的源命名空间声明**。该处 `remapNamespace` 取 `IntermediaryNamespaces.intermediary(project)`，对 Forge 返回 `SRG`（`IntermediaryNamespaces.intermediaryNamespace` 的 `case FORGE -> MappingsNamespace.SRG`），但它的实际输入 `atPatchedJar` 是 **notch** 名（实测 `fd.class` / 字段 `a`）。源声明为 srg 而输入为 official，是第三处空间不一致；实施时须一并确认源声明是否应改为 official。

## 理由

- **单一映射源、单一重映射**是现代重映射工具链的正确形态。当前"整体送 official + Forge 单独送 named"是两套目标空间并存，任何跨边界的成员引用都会失配，`ModelLoader.a` 只是最先暴露的一例。
- 与 loom 标准流程对齐（`AbstractMappedMinecraftProvider` 已送 named），可避免后续每遇到一个跨边界引用就补一次映射。
- 改动集中在 `buildRemapper` 的目标取值与 AT 目标命名空间两处，不需要重排流水线结构，风险面比"先合并再整体重映射"小。

## 后果

- Forge 各车道的 patched jar 与 `minecraftMaven` 下的制品命名空间将从 official 变为 named，**已有缓存产物全部失效**，需重建。
- 由于改动点被所有 Forge 车道共用，1.20.1 / 1.21.1 / 26.2 必须同步回归。这三个车道当前状态正常，回归不通过即视为本次改动不可接受。
- AT 与重映射的目标命名空间从此存在耦合，**今后改任一处都必须同时检查另一处**，否则会重现"AT 静默失效"或"NoSuchMethodError"。这一耦合关系需在两处代码注释中互相指明。
- 本决策只统一命名空间，不改变 AT 规则的来源命名空间（仍为 srg），翻译链保持不变。

## 备选方案

- **方案 a：MC 重映射时把 forge.jar 作为额外输入/映射补充。** 落选原因：`remapPatchedJar` 已把 forge.jar 作为输入喂进同一个 remapper，再加"作为映射源"会与既有输入语义重复，且需要从 jar 反推成员表，复杂度高于改一个目标命名空间。
- **方案 c：在 MC 重映射的 `IMappingProvider` 中注入 Forge 成员表作为恒等映射。** 落选原因：它能修掉成员名，但只针对"MC 引用 Forge 成员"这一种组合；若将来出现"Forge 引用 MC 新增成员"或其他跨边界形态，仍要再补。属补丁式方案，不解决空间并存的根因。
- **保持 official 不变，改让运行期加载 official 变体。** 落选原因：运行期还加载 loom 标准流程产出的 named 制品（`AbstractMappedMinecraftProvider` 第 541 行明确送 named），改运行期等于对抗 loom 既有设计，影响面大于改 Forge 侧的目标取值。

## 证伪后的修正

实测否定了「改目标命名空间」这条路，并给出真正的约束：

**问题不在目标空间，而在映射表的管辖范围。** `ModelLoader` 是 Forge 类，其成员名不在 MC 映射表内。因此任何「对 MC jar 做重映射」的做法都无法翻译对 Forge 成员的引用——映射表里根本没有这些条目。

可行的方向是：让 Forge 类的成员名在**被 MC 引用之前**就已经是最终名，再把这份已定名的 Forge jar 作为 MC 侧重映射的 **classpath 视图**提供进去。这样 MC 侧重映射在解析 `ModelLoader.a` 时，能从 classpath 里看到该方法在 Forge jar 中的名字，从而完成翻译。

`patchForge` 当前已在做 srg→named（`remapForgeToNamed`），但该产物没有作为 classpath 参与 MC 侧重映射。下一步应先实测「把已 named 的 forge.jar 作为 `readClassPath` 喂给 MC 侧重映射」能否翻出 `setupModelRegistry`，再决定是否落代码——不再直接改流水线结构。

