# MultiPlatformModTemplate

> 多平台 Minecraft mod 玩法**脚手架 / 模板**：**玩法写一次，平台与版本的差异交给脚手架。** 克隆本模板，在平台无关的 L0 写一份玩法逻辑，即可落地到任意服务端平台（Paper/Folia/Sponge）+ 任意客户端平台（Fabric/Forge/NeoForge），并桥接"服务端软件 ↔ 模组加载器"。

## 状态

骨架阶段（v0.1.0）：规格 / 架构 / 治理已建立，正按第一期推进核心分层与三平台冒烟验证。代码实现随后续迭代落地。

## 架构一览

五层同心结构，依赖只向内（详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)）：

```
L0 core-domain   功能域：玩法规则 + 领域模型 + 端口(Port) + 自有 EventBus(域间解耦)   ← 玩法只写这里
L1 core-runtime / core-server / core-client / protocol            框架编排 / 端逻辑 / 跨端协议
L2 platform-spi  平台抽象 SPI + PlatformProvider + FeatureGate     平台可插拔的唯一机制
L3 platform-bukkit（含 Paper/Folia 系列·FeatureGate）+ sponge/fabric/forge/neoforge（各独立 includeBuild）  各平台胶水
L4 各平台内 version-api + vX_Y                                     版本差异隔离
```

L0/L1 不含任何平台 / 版本代码；跨平台、跨版本的差异全部被 L3/L4 关在接口之后。

## 能力

- 一份玩法逻辑跨平台 / 跨版本复用（L0 零平台依赖，可纯 JVM 测试）。
- 平台可插拔：新增平台 = 实现 SPI + ServiceLoader 注册，不改公共层。
- 多版本适配：锚点 1.12.2 / 1.20.1 / 1.21.1 / 26.2（26.2 为 MC 今年最新版本号），前向可扩展（加版本 = 加一个 `vX_Y` 模块）。
- 任意服务端 + 任意客户端组合互通：经单一真源的跨端协议 + 版本协商。

## 结构

- 根包：`top.wcpe.mc.mpmt`（含项目名段；各模块按 `top.wcpe.mc.mpmt.<层>.<模块>` 组织）。
- `core-*` / `protocol`：平台无关核心（Java 8 + Lombok）。
- `platform-spi`：平台抽象 SPI。
- `platform-*`：各平台胶水（按各 loader 最低 JDK 编译）。
- `smoke`：架构验证用的最小冒烟特性（不发布，非产品玩法）。
- `docs/`：规格与决策文档。`.claude/rules/`：防漂移红线。

## 文档导航

- 需求：[`docs/PRD.md`](docs/PRD.md)
- 架构：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- 接口：[`docs/API.md`](docs/API.md)
- 运维：[`docs/OPERATIONS.md`](docs/OPERATIONS.md)
- 安全：[`SECURITY.md`](SECURITY.md)
- 决策：[`docs/adr/`](docs/adr/)
- 演进与维护：[`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md)
- 变更史：[`CHANGELOG.md`](CHANGELOG.md)

## 快速开始

构建模块尚未落地（见 [`docs/OPERATIONS.md`](docs/OPERATIONS.md)）。落地后此处补全：克隆 → Gradle 构建对应平台产物 → 放入服务端 `plugins/` 或客户端 `mods/`。

## 约定

贡献 / 提交 / 分支 / 文档同步约定见 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) 与 [`.claude/rules/`](.claude/rules/)。提交信息中文、遵循 Conventional Commits。

## 许可

[MIT](LICENSE)。
