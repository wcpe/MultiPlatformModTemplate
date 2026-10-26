# `mc-shared` — 跨 lane 的 MC 源码共享层

> 决策依据：[ADR-0028](../docs/adr/0028-mc-shared-cross-lane-source-layer.md)

## 这是什么

**碰 MC、但不碰加载器**的代码放这里，供多个平台车道在编译期合并复用。

它是**源码树，不是 Gradle 模块**——没有 `build.gradle.kts`，不写进 `settings.gradle.kts`。
每个命中的车道把它加进自己的 `setSrcDirs`，用**该车道自己的 MC API** 编译一次。

### 准入门槛（两条都要满足，且不设第三条）

1. **碰 MC** —— 引用 `net.minecraft` / `com.mojang` 等 Minecraft 类型
2. **不碰加载器** —— 不引用 `net.fabricmc` / `net.minecraftforge` / `net.neoforged` / `org.spongepowered`

### 什么**不**放这里

- **零 MC 的平台专属实现**（如 `FabricChannel`、`FabricClientNetwork` 这类既不碰 MC、又带 fabric 语义的纯 Java）：它们不属于本层，也不属于 `core`（平台中立层），更不能放进 `platform` 下的共享模块。**它们应当留在各自车道内，接受跨版本重复**——这是分层结构的必然结果。
- **加载器相关代码**：加载器差异由 `platform/<loader>/` 承担，不上提到本层。

本层**不按加载器分目录**。曾考虑过给挂载条件增加「加载器维度」以便共享上述零 MC 代码，因与「不碰加载器」的定义自相矛盾而被否决，见 [ADR-0031](../docs/adr/0031-mc-shared-loader-dimension.md)（已弃用，保留为否决记录）。

## 为什么不能是 Gradle 模块

跨版本、跨命名组时不存在可复用的二进制产物（1.12.2 是 MCP/SRG、1.20.x 是 mojmap、26.1+ 是无混淆）。
预编译一次就只能钉死在一个版本上，机制不成立。

## 目录结构

```
mc-shared/
├── README.md                      本文件
├── common/                        双端共享（所有车道）
│   └── src/{main,test}/java/top/wcpe/mc/mpmt/mcshared/common/
├── client/                        仅客户端（fabric / forge / neoforge）
│   └── src/{main,test}/java/top/wcpe/mc/mpmt/mcshared/client/
├── server/                        仅服务端（全部车道）
│   └── src/{main,test}/java/top/wcpe/mc/mpmt/mcshared/server/
├── gensrc/                        版本特化实现（直挂真源，不做文本替换）
│   └── <range>/{common,client,server}/src/main/java/
└── mappers/                       符号映射（作用于复制副本）
    └── <common|client|server>/to-<range>.txt
```

## 三条硬规则

1. **端按源目录分，不按包分。** `common/` `client/` `server/` 是三个独立源目录，挂载是**纯加法**——车道只加自己需要的端。
   禁止「整目录 srcDir + exclude 掉不需要的端」：加法漏挂会编译失败（可见），减法漏 exclude 会静默把不该有的代码编进产物。
2. **`mc-shared/` 下禁止出现 `build.gradle.kts`。** 出现即架构违规，由守卫拦截。
3. **不得引用加载器 API。** 允许 `net.minecraft` / `com.mojang` / `blaze3d` / LWJGL；禁止 `net.fabricmc.*` / `net.minecraftforge.*` / `net.neoforged.*` / `org.spongepowered.*`。需要加载器 API 的代码留在车道里。

## 包名约定

包名与源目录同构，便于一眼看出归属：

| 源目录 | 包 |
|---|---|
| `common/` | `top.wcpe.mc.mpmt.mcshared.common.*` |
| `client/` | `top.wcpe.mc.mpmt.mcshared.client.*` |
| `server/` | `top.wcpe.mc.mpmt.mcshared.server.*` |

包名只用于**可读性**，隔离由源目录保证。

## 域分组

`mc-shared` 不按域分目录层级，域体现在包名与切片名前缀里：

```
client/src/main/java/.../mcshared/client/combat/     ← 域分组在包级
client/src/main/java/.../mcshared/client/render/     ← 跨域通用工具
gensrc/1.21.5+/client/src/main/java/.../combat/
```

## 版本区间语法

`gensrc/<range>/` 与 `mappers/<端>/to-<range>.txt` 的 `<range>` 使用同一套写法：

| 写法 | 含义 |
|---|---|
| `1.12` | 仅 1.12.x |
| `1.20-1.21.4` | 闭区间 [1.20, 1.21.4] |
| `1.21.5+` | [1.21.5, ∞) |

比较时把 `1.21.4` 编码为整数 `12104`（与 ADR-0003 的 `vX_Y` 命名一致）。

## 命名组约束

| 组 | 版本 | 依据 |
|---|---|---|
| `mcp` | ≤ 1.12.2 | 无官方映射 |
| `mojmap` | 1.20.1 ~ 1.21.1 | 官方映射可用 |
| `unobfuscated` | 26.2 | 上游不混淆、不 remap |

`common/` 下的同一份源码只能被**命名组相同**的车道同时命中；跨组共享必须开新切片。

## 挂载规则（由 `build-conventions.mc-shared` 实现）

| 车道端能力 | 挂载 |
|---|---|
| 纯服务端（bukkit / sponge） | `common/` + `server/` + 命中的 `gensrc/<range>/{common,server}/` |
| 双端（fabric / forge / neoforge） | `common/` + `client/` + `server/` + 命中的 `gensrc/<range>/{common,client,server}/` |

`test` 源集按同样规则挂载。

**复制策略**：车道 MC 版本 == 基线版本时直挂真源；否则复制到 `build/generated/mc-shared/<端>` 并在副本上应用 `mappers/`，以保住 IDE 跳转与增量编译。

## 当前状态

**已落地两批内容**，`common/` `server/` `gensrc/1.20-1.21/` 目录已按需创建（不是预建空壳）。

### 根目录 = 所有命名组共用

| 端目录 | 已收敛内容 | 来源 |
|---|---|---|
| `common/` | `FabricMessagePort`、`FabricPlayerPort`、`FabricConnectionControlPort`（包 `top.wcpe.mc.mpmt.mcshared.common.capability`） | fabric 1.20.1 / 1.21.1 / 26.2 **三份**完全相同 |
| `server/` | `FabricConnectionHandle`（包 `top.wcpe.mc.mpmt.mcshared.server.net`） | 同上；只被服务端使用 |

### `gensrc/1.20-1.21/` = 仅 mojmap 组共用

`FabricNetworkBindings`、`FabricResourceLocations`、`FabricWorldPort`、`FabricPlatformBootstrap`（common）、
`FabricServerTransport`、`FabricMachineCodeCommands`（server）。

这些在 1.20.1 与 1.21.1 之间完全相同，但在 **26.2 里内容不同**（`ResourceLocation` → `Identifier` 等），
故只能进版本区间目录，不能进根 `common/`。26.2 保留自己的副本。

挂载入口：[`McSharedSources`](../build-logic/build-conventions/src/main/kotlin/buildconventions/McSharedSources.kt) +
[`McSharedRanges`](../build-logic/build-conventions/src/main/kotlin/buildconventions/McSharedRanges.kt)，
由 `build-conventions.fabric` 在 `afterEvaluate`（需要读车道 MC 版本）**纯加法**追加
`common + client + server` 与命中区间的 `gensrc/<range>`（目录不存在时自动跳过）。

已验证：1.20.1 / 1.21.1 从 `gensrc` 取这 6 个类，26.2 用自有副本，三者 build 全绿。

尚未实现：`mappers/` 符号映射、孤儿检查、架构守卫（`build-conventions.architecture`）。
`gametest` 源集暂不挂载 `mc-shared`——它是独立源集，需单独接线，当前相关文件仍留在车道内。
