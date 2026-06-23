# ADR-0012：打包与依赖隔离——第三方依赖 relocate，core 如何进各平台产物

## 状态
已接受

## 背景
评审指出两处被遗漏的硬问题：① Bukkit 生态所有插件共享类加载空间，未 relocate 直接打包 `snakeyaml`/`gson` 等会与服务端 / 其它插件的不同版本冲突崩（LinkageError / NoSuchMethod）——这是 Bukkit 打包最经典翻车点；② core-* 经 includeBuild 依赖替换被 Loom/ForgeGradle/NeoGradle 消费时，会与各 loader 的 remap / 打包约定打架，"配一行 substitution 就通"过于乐观。

## 决策
1. **第三方运行期依赖统一 relocate**：各平台产物 shadow 时把 `snakeyaml`/`gson` 等 relocate 到 `top.wcpe.mc.mpmt.libs.*`，避免与宿主 / 其它插件冲突。
2. **core-* 进各平台产物的方式逐 loader 明确**：Bukkit/Sponge 用 shadow 把 core shade 进插件 jar（含上条 relocate）；Fabric 用 Loom 的非重映射依赖 + include(JiJ) 或 shade；Forge/NeoForge 用 shadow / jarJar。**core 是平台无关纯 Java、不参与重映射**——必须明确标注为非 mod 依赖。
3. **M0 先做最小打包 spike**：1 个 core 模块 + 1 个 Fabric includeBuild，验证 core 被正确打进 remapped jar 且不被误 remap；若 includeBuild 依赖替换在 Loom/ForgeGradle 下不顺，**回退为"core 发布到 mavenLocal、各 loader 以 implementation+shadow 消费"**（Loom/ForgeGradle 社区主流可行路径）。

## 理由
- relocate 是 Bukkit 打包硬需求，不做必冲突。
- core 打包方式各 loader 不同，必须逐一调通，而非假定统一。
- spike 先验证打包链路再铺平台，降低 M0（构建骨架）风险。

## 后果
- 正面：避免类冲突；打包链路有明确落点。
- 负面：每个 loader 的打包配置要单独调；relocate 增加构建配置。
- 约束：共享层代码不依赖 relocate 后的包名（relocate 在构建期做）；"核心类正确进各 mod jar 且不被 remap"列为各平台接通验收点。

## 备选方案
- **不 relocate**：Bukkit 必类冲突——否决。
- **假定 includeBuild 替换免费打通**：评审指出 Loom/ForgeGradle 重映射环境易踩坑——否决，改为 spike 先行 + 必要时 mavenLocal 回退。
