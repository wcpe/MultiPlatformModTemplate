# 功能规格：Gradle 复合构建骨架 + 打包 spike + 跨栈字节对齐 spike（M0）

> 状态：开发中　·　关联 PRD：FR-07/FR-08/FR-09 前置（M0 构建骨架），ADR-0007 / ADR-0012 / ADR-0016 落地验证　·　分支：feature/build-skeleton
> 对应 `network-handshake-machine-code-ban.md` §4 首个任务"M0 构建骨架 + 打包 spike + 跨栈 spike"。本期**只做构建骨架 + 两个 spike**，平台胶水（Paper→Fabric→Forge）后续各轮再做（经用户确认分阶段）。

## 1. 背景与目标

为后续全部平台胶水（L3/L4）铺好**构建地基**并**先验证两处被评审点名的硬风险**，避免铺平台后才翻车：

- **构建骨架**：建立 Gradle 复合构建（Kotlin DSL）——共享核心为根构建常规 `java-library` 模块（Java 8）；带专属插件的平台经 `includeBuild` 隔离（本期落地 Fabric/Loom 一例验证机制，ADR-0007）。
- **打包 spike**（ADR-0012）：验证 core 纯 Java 被正确打进 Fabric 的 remapped jar、**不被误 remap**，且第三方依赖（snakeyaml）被 relocate 到 `top.wcpe.mc.mpmt.libs.*`。
- **跨栈 spike**（ADR-0006 / network spec §3.4）：以**自动化字节等价校验**证明同一逻辑包，经 Fabric 的 `FriendlyByteBuf` 写出的字节，与 Paper 端 `PluginMessageListener` 收到的 `byte[]`（普通 `DataOutputStream` 路径）**逐字节一致**——为后续协议单一真源铺路。

属第一期（P1）的 MVP 前置。

## 2. 需求（要什么）

- 范围内：
  1. Gradle wrapper（8.10.2）+ 根 `settings.gradle.kts` / `build.gradle.kts` / `gradle.properties`。
  2. `core-domain` 根模块：`java-library`、严格 Java 8（**JDK 8 工具链**编译，API 面即 JDK 8，强于仅 `--release 8`）、含**一个最小真实类**（非空壳，`Mpmt` 命名空间常量）、**零第三方运行期依赖（保持 L0 纯净）**。relocate 验证用的 snakeyaml 依赖挂在 `platform-fabric` 构建上，不污染 L0。
  3. `platform-fabric` 独立 `includeBuild`（Loom，MC 1.20.1，**Mojmap**，Java 17）：经依赖替换或 mavenLocal 回退消费 core；shadow 把 core shade 进产物并 relocate snakeyaml；`remapJar` 消费 shadow 产物产出 remapped mod jar。
  4. 打包 spike 校验：自动化检查产物 jar 内 core 类在原包名下、snakeyaml 在 relocate 后包名下、MC 类已 remap。
  5. 跨栈 spike 校验：`platform-fabric` 内一条纯 JVM 自动化测试，断言 Fabric buf 字节 == 普通 byte[] 字节。
- 不做（范围外）：
  - **平台胶水本体**（SPI 实现、TransportPort、命令、版本适配 vX_Y）——属后续平台轮次。
  - **Bukkit/Forge/Sponge/NeoForge 模块**——本期不建（不预建空壳，scope-discipline §3）；Bukkit relocate 验证随 Paper 平台轮次。
  - **真·实机 spike**（启动 Paper 服 + Fabric 客户端抓线缆）——经用户确认改为自动化字节等价校验，实机互通留到平台集成的 realserver 验收。
  - protocol / domain / core-runtime 等其它核心模块——各自后续轮次（M1+）。

## 3. 设计（怎么做）

### 3.1 复合构建结构（ADR-0007）
```
/                       根构建：core-domain（常规 java-library）+ includeBuild(platform-fabric)
  core-domain/          L0 最小真实类，Java 8（JDK 8 工具链）；零第三方运行期依赖
  platform-fabric/      独立构建（自带 settings + pluginManagement，仅 Loom）；snakeyaml 在此 shade+relocate
```
- **core 进 Fabric 的消费路径**是 ADR-0012 点名的风险点。先试 `includeBuild` 依赖替换；若 Loom 环境下不顺，**回退到 ADR-0012 sanctioned 的"core 发 mavenLocal + Fabric implementation+shadow 消费"**。本 spike 的产出之一就是**确定并记录可行路径**（见 §5 与下方"spike 结论"）。

### 3.2 打包链路（ADR-0012）
- `platform-fabric` 同时应用 `fabric-loom` 与 `com.gradleup.shadow`。
- `shadowJar`：bundle core（纯 Java、**非 mod 依赖、不参与 remap**）+ relocate `org.yaml.snakeyaml` → `top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml`；排除 Minecraft/Fabric Loader 依赖。
- `remapJar` 以 `shadowJar` 产物为输入（`remapJar.inputFile = shadowJar.archiveFile`），产出最终 remapped mod jar。
- 映射：`loom.officialMojangMappings()`（ADR-0016）。

### 3.3 跨栈字节对齐（ADR-0006 / network spec §3.4）
- spike 用一个**一次性最小包**（非产品协议，spike 专用）：包头 `[protocolVersion:u8][packetId:u8]` + 一个 varint + 一个 utf。
- 写两遍：① 经 Fabric `FriendlyByteBuf`（Loom 提供的 MC 类）；② 经普通 `java.io.DataOutputStream` 模拟 Paper 端 `byte[]` 读法。断言两者 `Arrays.equals`。
- 证明 Fabric 自定义 payload 字节布局可被 Paper 插件通道按相同字节解读，为协议单一真源（后续 M2）扫清"字节对不上"风险。

> 涉及的架构决策见 ADR-0007（复合构建隔离）、ADR-0012（打包 / relocate）、ADR-0016（映射策略）；本 spec 不重复其决策正文。

## 4. 任务拆分
- [x] T0 Gradle wrapper（8.10.2）+ 根 settings/build/properties
- [x] T1 core-domain：最小真实类 + Java 8 强制（用 JDK 8 工具链，强于 `--release 8`，见结论）
- [x] T2 platform-fabric includeBuild：Loom + Mojmap + 消费 core（确定为 includeBuild 依赖替换，未回退 mavenLocal）
- [x] T3 打包链路：shadow shade core + relocate snakeyaml + remapJar 消费 shadow 产物
- [x] T4 打包 spike 校验：产物 jar 内类归属自动化检查（`verifyPackaging` 任务）
- [x] T5 跨栈 spike：Fabric buf vs 普通 byte[] 字节等价自动化测试（12 例）
- [x] 文档同步：本 spec 结论、ARCHITECTURE §6（构建/映射现状）、ADR-0012/0016 落地结论、CHANGELOG

## 5. 验收标准
- **构建通过**：根构建 + platform-fabric includeBuild 全量构建成功（`gradlew build`），命令 + 关键输出为证。
- **core Java 8 强制**：core-domain 以 JDK 8 工具链编译通过；故意误用 Java 9+ API 时编译失败（验证强制生效）。
- **打包 spike 通过（自动化）**：最终 remapped Fabric jar 内——core 类位于 `top/wcpe/mc/mpmt/core/domain/`（**未被 remap**）；snakeyaml 位于 `top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/`（已 relocate）；jar 含 `fabric.mod.json` 且 MC 引用已 remap。以自动化校验任务/脚本输出为证。
- **跨栈 spike 通过（自动化）**：字节等价测试绿（Fabric buf 字节 == 普通 byte[] 字节）。
- **spike 结论已记录**：core 进 Fabric 产物的可行路径（includeBuild 替换 or mavenLocal 回退）写入本 spec + 同步 ADR-0012 落地结论。
- 本期**无实机维度**（实机互通留到后续平台 realserver 验收）；故"全绿"即本 M0 阶段 done。

## 6. 风险 / 待定
- **includeBuild 依赖替换在 Loom 下的可行性**：ADR-0012 已预警"配一行 substitution 就通"过于乐观；本 spike 即为定论，必要时按 ADR-0012 回退 mavenLocal。
- **shadow + Loom remap 串联顺序**：`remapJar` 必须吃 `shadowJar` 产物且 core 不被 remap；为本 spike 主要调试点。
- **Gradle/Loom/JDK 版本匹配**：Loom 1.7 + Gradle 8.10.2 + 守护进程 JDK（21）、Fabric 编译 JDK 17 工具链；版本不匹配会构建失败。
- **版本来源重复（后续轮次处理）**：`fabric.mod.json` 的 `depends`（fabricloader/minecraft 版本约束）与 `build.gradle.kts` 的 `mcVersion`/`loaderVersion` 常量是两套来源，存在静默漂移风险。M0 不处理（两值一致、不触发问题）；后续接入 Fabric 胶水时经 `processResources` 的 `inputs.property`+`expand` 注入或集中到 `gradle.properties` 同源。

---

## spike 结论（实测）

环境：Gradle 8.10.2（守护进程 JDK 21）、Loom 1.7.4、MC 1.20.1 + Mojang 官方映射、core 用 JDK 8 工具链（corretto-1.8.0_482，Gradle 自动探测）、Fabric 用 Java 17 工具链。`gradlew :platform-fabric:build` 全绿（守护进程下约 31s）。

1. **core 消费路径 = includeBuild 依赖替换（成立，未回退 mavenLocal）**。
   - 结构：根构建含 `core-domain`（常规 java-library）+ `includeBuild("platform-fabric")`；`platform-fabric` 自身 `includeBuild("..")` 反向消费 `top.wcpe.mc.mpmt:domain`。
   - 该互相 includeBuild（root↔fabric）在 Gradle 8.10.2 下配置与解析均正常，`:core-domain:jar` 被正确替换进 `platform-fabric` 编译/打包。
   - **ADR-0012 落地结论**：includeBuild 依赖替换在 Loom 1.7 下可行，**本期不触发"core 发 mavenLocal + shadow 消费"的回退**（回退方案保留为后续若遇 ForgeGradle/NeoGradle 不顺时的备用）。

2. **打包链路 = jar → shadowJar → remapJar（成立）**。
   - core 纯 Java 经 `implementation` 编译可见 + `shadowBundle` 配置被 shadow 打入；`remapJar.inputFile` 指向 `shadowJar` 产物。
   - 自动化校验（`verifyPackaging`）确认最终 `mpmt-fabric-0.1.0.jar`（294 条目）内：
     - core 类 `top/wcpe/mc/mpmt/core/domain/Mpmt.class` 以**原包名**存在（**未被 remap**）；
     - snakeyaml 已 **relocate** 到 `top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/`，原包名 `org/yaml/snakeyaml/` 不存在；
     - `fabric.mod.json` 存在且 `version` 由 VERSION 注入为 `0.1.0`；产物内无 `net/minecraft/*`（由 loader 提供）。

3. **跨栈字节对齐（成立，自动化）**：`CrossStackByteAlignmentTest` 12 例全绿——典型 spike 包（u8+u8+varint+utf）及 VarInt 边界（0/127/128/255/256/16383/16384/2097151/2097152/MAX_VALUE）下，Fabric `FriendlyByteBuf` 写出的字节与普通 `DataOutputStream` 路径逐字节一致。Fabric 自定义 payload 字节布局可被 Paper 插件通道按相同字节解读，**为协议单一真源（M2）扫清"字节对不上"风险**。

4. **Java 8 强制（成立）**：core 用 JDK 8 工具链编译；JDK 8 `javac 1.8.0_482` 拒绝 `List.of(...)`（退出码 1，"找不到符号 method of"），`Arrays.asList(...)` 正常（退出码 0）——证明误用 Java 9+ API 会编译失败（ADR-0004）。

> 后续平台轮次（Paper/Fabric/Forge 胶水）沿用本结论：Bukkit 走根构建常规模块 + shadow（含 relocate），Forge/NeoForge 各为独立 includeBuild、按此打包链路套用并各自验证 core 进产物不被 remap。
