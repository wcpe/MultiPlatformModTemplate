# ADR-0031：跨 lane 共享源码的第二维度——加载器归属

## 状态

**已弃用（不采用）**。本文保留为否决记录，防止后续重复提出同一方案。

弃用理由（用户指出，2026-10-19）：`mc-shared` 的定义是「碰 MC、但不碰**加载器**」（[ADR-0028](0028-mc-shared-cross-lane-source-layer.md) 决策 1、`mc-shared/README.md` 硬规则）。给这一层增加加载器维度，等于让一个定义上「与加载器无关」的层去认识加载器——**自相矛盾**，且把平台层的职责泄漏进共享层。加载器的差异本应由 `platform/<loader>/` 承担，不应上提到 `mc-shared`。

更关键的是：拟迁移的那 23 个文件（`FabricChannel`、`FabricClientNetwork` 等）**既不碰 MC、也不碰加载器**，两个准入条件都不满足。它们不属于 `mc-shared`，也不属于 `core`（平台中立层），也不能放进 `platform/` 下的共享模块（护栏：共享层不得放进 `platform/`）。**结论：它们应当留在各自车道内，接受重复——这是分层结构的必然结果，不是妥协。**

---

## 背景

[ADR-0028](0028-mc-shared-cross-lane-source-layer.md) 建立的 `mc-shared/` 目前只按**版本区间**决定挂载：`mc-shared/common/` 全车道共用，`mc-shared/gensrc/<range>/` 按车道 MC 版本匹配（[ADR-0030](0030-domain-module-layout.md) 之后已落地并验证 10 个类）。

在实际收敛重复代码时发现一个缺口。以 fabric 车道为例，跨版本完全重复的源码分两类：

| 类别 | 数量 | 现状归属 |
|---|---|---|
| 碰 MC、不碰加载器 | 4 个（已进 `common/`）+ 6 个（已进 `gensrc/1.20-1.21/`） | 已收敛 |
| **零 MC 的 fabric 专属实现** | **23 个**（`FabricChannel`、`FabricClientNetwork`、`FabricVersionAdapter`、`FabricServerServices`、`SimTestSupport` 等） | 仍是三份副本 |

这 23 个文件在 fabric 1.20.1 / 1.21.1 / 26.2 三条车道的源码**逐字节相同**，但它们带 fabric 语义（包名 `top.wcpe.mc.mpmt.platform.fabric.*`、类名 `Fabric*`），只能被 fabric 车道消费。

问题在于：**`mc-shared` 现有的挂载条件里没有"加载器"这一维**。若把这 23 个文件放进 `gensrc/1.20-1.21/`，版本区间 1.20-1.21 会同时命中 `forge-1.20.1`、`neoforge-1.20.2` 等车道——它们会被迫编译 fabric 专属代码，直接失败。放进 `common/` 同理且更糟（26.2 也会命中）。

## 决策

为 `mc-shared` 的挂载条件增加**加载器维度**，与既有版本区间维度**正交**：

```
mc-shared/
├── common/                         所有加载器 × 所有版本
├── client/  server/                同上（按端）
└── gensrc/
    ├── <range>/                    仅按版本（既有）
    └── fabric/<range>/             加载器 + 版本两个条件同时满足才挂载
```

挂载判定：`loader == 当前车道加载器 && range 命中当前 MC 版本`。目录名解析顺序为
`<loader>/<range>`，无加载器前缀者视为"所有加载器"（向后兼容既有 `gensrc/1.20-1.21/`）。

## 理由

- **不引入新顶层目录**：复用 `mc-shared` 已有结构，只是把选择条件从 1 维扩到 2 维。护栏「顶层目录语义唯一」不受影响，也不需要 `platform/` 下放共享层（那是明确的红线）。
- **与既有机制同构**：区间匹配已实现且有 7 个行为测试（`McSharedRangesTest`），新增的加载器判定只是在同一处多一个条件，不需要新的匹配引擎。
- **失败可发现**：加载器条件写错会导致"该挂载的没挂载"→ 立即编译失败，而不是静默用错版本。
- **向后兼容**：现有 `gensrc/1.20-1.21/` 与新 `gensrc/fabric/1.20-1.21/` 共存，前者语义不变。

## 后果

正面：

- 23 个零 MC 的 fabric 实现可以从三份副本收敛为一份，消除约 190 处引用所对应的重复维护。
- 后续 forge / neoforge / sponge 的同类重复可沿用同一机制，不需要再改机制。

负面与约束：

- **`mc-shared` 的定位需要放宽**：原表述是"碰 MC、不碰加载器的代码"（ADR-0028 决策 1、`mc-shared/README.md`）。本决策后它承载"跨 lane 共享源码"，**碰不碰 MC 都可以**，但**仍然禁止 import 加载器 API**（`net.fabricmc` / `net.minecraftforge` / `net.neoforged` / `org.spongepowered`）——这条硬规则不变，否则跨版本编译能力失效。
- 目录名多了一层嵌套，`McSharedRanges.matches` 与 `McSharedSources.mountVersionRanges` 需要相应扩展并补测试。
- 加载器标识需要从车道显式传入（fabric 车道传 `fabric`），不能从工程路径推导——推导会在 `platform/<loader>/` 之外的位置失效。

## 备选方案

- **新建 `platform/<loader>/shared/` 共享 Gradle 模块**：`platform/fabric/fabric-api` 已有类似先例（非车道的共享模块）。但护栏明确规定「`platform/` 下只放平台车道，共享层不得再放进 `platform/`」，故不采用。
- **下沉到 `core/` 共享模块**：这 23 个文件带 fabric 语义，且依赖 `core:runtime` / `core:server` / `core:client` / `platform:spi`；放进 `core/` 会让核心层反向承载平台专属实现，违反「顶层目录语义唯一」。已否决。
- **接受重复，不做迁移**：零风险、零成本，但这 23 个文件在三条车道各自演进时会持续漂移；且 `gensrc` 机制已验证可行（6 个文件已收敛），不迁移等于放着现成机制不用。
