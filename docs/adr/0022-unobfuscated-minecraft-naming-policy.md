# ADR-0022：Minecraft 26.1+ 无混淆命名与加载器构建策略

## 状态

已接受

## 背景

[ADR-0016](0016-mappings-policy.md) 以“存在 Mojang 官方映射时统一使用 Mojmap，否则按加载器回退”为前提，覆盖的是 Minecraft 仍以混淆制品发布的版本。Minecraft 26.1 起，上游游戏制品不再混淆，也不再发布对应的 Mojang mappings；映射制品缺失不代表 Fabric 不支持该游戏版本，而是旧的反混淆前提已经消失。

Fabric 26.1+ 因此需要使用面向无混淆制品的新构建链路。ForgeGradle 仍可能在自身工具链中执行名称或制品转换，但这些内部步骤不等同于依赖 Mojang mappings 文件。为避免把“没有 mappings”误判成平台不可接入，或把不同加载器的构建细节误写成同一映射策略，本 ADR 取代 ADR-0016。

## 决策

- **Minecraft 26.1+ 使用上游制品中的原始命名**，不声明 Mojang mappings、Yarn 或其他第三方映射依赖。
- **Fabric 26.1+ 使用 `net.fabricmc.fabric-loom` 的无混淆构建链路**，不使用 `officialMojangMappings()`、intermediary 命名或 `remapJar` 作为最终制品流程。
- **Forge 26.1+ 按对应 ForgeGradle 工具链构建**；ForgeGradle 内部的名称或制品转换属于加载器工具链实现，不得据此宣称该版本仍存在或依赖 Mojang mappings 文件。
- **Minecraft 26.1 以前的混淆版本继续沿用 ADR-0016 的选择规则**：有 Mojang 官方映射时优先 Mojmap；无官方映射时按加载器可用映射回退。
- 命名与加载器构建差异只允许留在 L3/L4；L0–L2 不引用 Minecraft 类型，不受映射或无混淆策略影响。

## 理由

- 直接匹配 Minecraft 26.1+ 的上游发布形态，避免请求不存在且已无必要的 mappings 制品。
- 保留旧混淆版本的既有规则，不为 26.1+ 的变化破坏 1.12.2、1.20.1、1.21.1 等锚点。
- 分开描述 Fabric 与 Forge 的构建链路，避免把 ForgeGradle 的内部处理误认为 Mojang mappings 仍在发布。
- 不引入第三方映射源或额外命名层，减少供应链与跨加载器命名漂移。

## 后果

- 正面：Fabric 26.2 可以在没有 Mojang mappings 与 Yarn 的情况下接入，不再把上游无混淆误报为硬阻塞。
- 正面：旧锚点继续保持原有命名规则，26.1+ 则直接使用上游原始命名，版本边界明确。
- 负面：Fabric 26.1+ 与旧 Fabric 车道的插件配置、依赖声明和最终制品任务不同，不能机械复制旧构建脚本。
- 约束：新增锚点时必须先判断上游制品是否混淆，再选择本 ADR 对应的构建分支；不得仅以 mappings 元数据是否存在判断平台可用性。
- 约束：各加载器的内部转换细节仍由其独立构建负责，不得泄漏到 L0–L2。

## 备选方案

- **不建 Fabric 26.2 格，缩为 Paper + Forge 双车道**：平台实际可接入，仅因旧映射假设删格会缩小 FR-16 验证范围——否决。
- **寻找第三方 mappings 源**：无混淆制品不需要额外映射，反而增加来源、许可与维护成本——否决。
- **仅以 intermediary 命名编译并继续 remap**：延续了已经失效的旧制品模型，也不符合 Fabric 26.1+ 的无混淆链路——否决。
- **继续声明 Mojmap**：Minecraft 26.1+ 不再发布对应文件，构建无法解析且概念上多余——否决。
