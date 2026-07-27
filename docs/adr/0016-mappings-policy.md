# ADR-0016：反混淆映射策略——锚点有官方映射用官方(Mojmap)，无官方走各 loader 自带

## 状态
已被 [ADR-0022](0022-unobfuscated-minecraft-naming-policy.md) 取代

## 背景
模组加载器（Fabric/Forge/NeoForge）编译期需要一套把 Minecraft 混淆类名还原为可读名的**反混淆映射**。可选项主要有：Mojang 官方映射（Mojmap，Mojang 自 1.14.4 起随版本发布）、社区 Yarn（Fabric）、MCP/SRG（Forge）等。映射选择影响**所有 L3/L4 平台侧代码**里对 Minecraft 类型的命名引用，跨平台混用会让同一份平台胶水在 Fabric 与 Forge 两侧命名不一致、增加模板读者心智负担；且后期切换映射代价大（要重写所有 NMS 引用）。本项目锚点版本含 1.20.1（有官方映射）与 1.12.2（**无**官方映射，Mojang 当年未发布）。

## 决策
- **锚点版本存在 Mojang 官方映射时，Fabric(Loom) 与 Forge(ForgeGradle) 统一使用 Mojang 官方映射（Mojmap）**：Loom 用 `loom.officialMojangMappings()`，ForgeGradle 用 `mappings channel: 'official'`。
- **该版本无官方映射时（如 1.12.2），各 loader 回退到其自带可用映射**（Forge 1.12.2 用 MCP/SRG；该版本无 Fabric，不涉及 Yarn）。
- 即：**有官方选官方，没官方走各自**——以官方映射为统一首选，仅在官方缺位时按平台回退。
- 映射只影响 L3/L4 平台胶水的命名；**L0–L2 不引用任何 Minecraft 类型，与映射无关**（见 ADR-0001 / ADR-0004）。

## 理由
- 官方映射命名一致、许可清晰、现代主流，模板可读性最好，且 Fabric 与 Forge 两侧命名一致，降低"同一胶水两套名"的负担。
- 老版本（1.12.2）无官方映射是历史事实，强行统一不可行，按平台回退是唯一务实选择。
- 把"有则官方、无则回退"写成显式策略，避免后续新增版本时随手选映射造成漂移。

## 后果
- 正面：1.20.1 / 1.21.1 / 26.2 等现代锚点两 loader 命名统一；映射来源有据可依。
- 负面：1.12.2（P2）Forge 侧将是 MCP/SRG 命名，与现代锚点命名不一致——但其本就被 L4 版本适配层隔离（ADR-0003），影响被关在 `v1_12` 内。
- 约束：新增版本时按本策略定映射；Fabric 侧不引入 Yarn 依赖（除非将来出现"官方缺位且必须 Fabric"的版本，届时另议）。

## 备选方案
- **Fabric 用 Yarn、Forge 用官方**：两侧命名不一致、模板读者负担更大——否决（用户已确认"有官方选官方"）。
- **全用 Yarn**：Forge/NeoForge 生态不以 Yarn 为主、跨 loader 不统一——否决。
- **全用 MCP/SRG**：现代版本已非主流、可读性差——否决。
