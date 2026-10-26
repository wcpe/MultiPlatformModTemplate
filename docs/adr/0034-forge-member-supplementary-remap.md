# ADR-0034：对最终 Forge jar 做基于 Forge 自身两版差异的补充成员名改写

## 状态

提议中（核心假设已通过端到端实验验证，待用户接受后实施）

> **实验验证记录（2026-10-21）**：本 ADR 的核心假设已实测跑通，结论如下。
>
> 实验程序：`.tmp/exp-remap/ExpForgeFix.java`（wcpe-loom 仓库内）。它按「同类内成员顺序对齐」比对 `forge-universal.jar` 与 `forge.jar`，建出 394 条成员映射，再对目标 jar 做 ASM 改写。
>
> 对运行期实际加载的 `generated-intermediate` 变体改写后：
>
> ```
> 改写前：invokevirtual ModelLoader.a:()Lnet/minecraft/util/registry/IRegistry;
> 改写后：invokevirtual ModelLoader.setupModelRegistry:()Lnet/minecraft/util/registry/IRegistry;
> ```
>
> 实验同时暴露两个决定成败的实施要点，已并入下方「决策」节：
>
> 1. **匹配必须忽略描述符**。`SimpleRemapper` 按 `owner.name+desc` 精确匹配时，因最终 jar 里该引用的描述符已是 named 形（`()L.../IRegistry;`），与映射键里的 srg 形（`()Lfm;`）不等，会静默不匹配——实测仅 2 个 class 被改写且目标引用未变。改为按 `owner + name` 匹配后，12 个 class 被改写，目标引用正确翻译。
> 2. **改写落点是 `AbstractMappedMinecraftProvider.saveRemappedJar()` 的产物**，不是 `MinecraftLegacyPatchedProvider` 的 `minecraftPatchedAtJar`。运行期 classpath 加载的是前者（`minecraftMaven` 下的 `generated-intermediate` 变体）；对后者改写无效（已验证）。

## 背景

[ADR-0033](0033-forge-artifact-namespace-named.md) 试图用「把 MC jar 重映射目标改成 named」解决 `ModelLoader.a()` 报错，已被实验证伪并弃用。证伪后继续排查，得到两条确定的事实：

**事实一：该引用由 Forge 的 binpatch 引入，不是 MC 原有代码。**

实测原始 notch jar（`minecraft-client.jar` 的 `cgc`）里 `ModelManager` 完全没有对 `ModelLoader` 的引用；该调用只出现在打过 Forge 补丁的产物中（补丁源 `patches-client.lzma`）。

**事实二：Forge 自己的两份 jar 里，同一成员的名字不同，且可可靠对齐。**

| jar | 同一方法的签名 |
|---|---|
| `forge-universal.jar`（srg 空间） | `public fm<cgd, cfy> a()` |
| `forge.jar`（named 空间，经 `remapForgeToNamed`） | `public IRegistry<...> setupModelRegistry()` |

进一步实测：两份 jar 中 `ModelLoader` 的成员数量一致（各 41 个），且按 class 文件中的出现顺序逐行对齐后，每一行的结构完全对应，仅类型名从 srg 形变为 named 形。**因此「按顺序对齐两版成员」可作为成员名映射的可靠来源，不需要依赖 MC 映射表。**

综合两条事实，问题的性质是：**Forge 的 binpatch 把 named 空间的成员引用写进了 notch 世界的 MC 类中，而该引用所指的 Forge 成员名没有任何 MC 映射表可查。** 这正是 ADR-0033 的两种做法（改目标空间、补 classpath 视图）都失败的原因——它们都在「用 MC 映射表翻译」，而 MC 映射表里根本没有 Forge 成员。

## 决策

在 Forge 合并之后、产物落位之前，增加一道**补充成员名改写**工序：

1. 以 `forge-universal.jar`（srg 空间）与 `forge.jar`（named 空间）的类结构为输入，按「同类内成员顺序对齐」建立 `srg 名 → named 名` 的反向映射；
2. 扫描最终 jar 中所有对 Forge 类的成员引用，命中映射则改写成 named 名；
3. 未命中的引用原样保留，并计入日志（不静默丢弃，便于后续排查）。

该工序只处理「对 Forge 类的成员引用」，不触碰 MC 自身成员，因此与既有 MC 映射流程互不干扰。

## 理由

- **只有这条路能覆盖该引用**：它不依赖 MC 映射表，改由 Forge 自身两版差异提供映射，而这份差异已实测可靠（41 个成员逐行对应）。
- **副作用可控**：只改写命中 Forge 类成员表的引用，MC 成员不受影响；未命中的引用保留原样并记日志。
- **不改变现有流水线结构**：它是合并之后的一道独立工序，不移除或重排 `patchForge` / `remapPatchedJar` / `mergeJars` 的任何步骤。
- 与备选的「跳过引入该调用的 binpatch」相比更安全：跳过补丁会连带移除 Forge 在 `ModelManager` 里的模型加载改造，可能造成更隐蔽的功能缺失。

## 后果

- 该工序位于所有 Forge 车道共用的合并路径上，**1.20.1 / 1.21.1 / 26.2 必须回归**。这三个车道当前状态正常，回归不通过即视为本次改动不可接受。
- 引入一条新的不变量：**最终 jar 中不得残留对 Forge 成员的 srg 名引用**。该不变量可作为校验项，在工序完成后自检并输出计数。
- 「按顺序对齐两版成员」依赖两份 jar 由同一份源码编译。若将来 Forge 的 universal 与 named 版本来源不一致（例如经过额外处理），对齐可能失真；届时需改为按混淆映射表反查。
- 工序会遍历最终 jar 的全部 class 做引用扫描，**构建耗时会增加**，需在实施时测量并记录基线。

## 备选方案

- **方案 1：查明并跳过引入该调用的 binpatch。** 落选原因：该补丁同时承载 Forge 在 `ModelManager` 里的模型加载改造，跳过会连带移除功能，副作用比本方案更难评估；且 Forge 后续版本可能引入更多同类引用，逐个跳过不可持续。
- **方案 3：改 MC jar 重映射目标空间／补 classpath 视图。** 已在 ADR-0033 中实测证伪：TinyRemapper 只按映射表翻译，classpath 只提供类型结构不提供名字映射，MC 映射表内无 Forge 成员，故两条路径均无效。
- **方案 4：让 Forge 侧在打补丁前先统一到 notch 空间。** 落选原因：需要把已 named 的 Forge 反向退回 notch，与既有 `remapForgeToNamed` 的工作方向相反，且 Forge 后续所有引用都要重来一遍，改动面大于本方案。
