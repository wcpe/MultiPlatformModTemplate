# MultiPlatformModTemplate

> 多平台 Minecraft mod 玩法**脚手架 / 模板**：**玩法写一次，平台与版本的差异交给脚手架。** 克隆本模板，在平台无关的 L0 写一份玩法逻辑，即可落地到任意服务端平台（Paper/Folia/Sponge）+ 任意客户端平台（Fabric/Forge/NeoForge），并桥接"服务端软件 ↔ 模组加载器"。

## 状态

当前正式版 **v0.2.0**：第一期 MVP 与第二期多版本矩阵（FR-12，1.21.1 / 1.12.2）已交付。第三期（FR-16 26.2 / FR-17 模板发布 / FR-18 上手文档）仍在开发：Paper、Fabric、Forge 26.2 的同轮 R7 权威报告与根 `:runP3R7Gate` 已通过，仓库已启用为公开 GitHub Template；但尚无 `v0.3.0` 或 GitHub Release，且仍缺用户第三期实机确认及 FR-18 的干净克隆复现。

**从模板起步**：本仓库已启用 GitHub Template；在 GitHub 选择 `Use this template` 创建新仓库后，见 [`docs/HOWTO-CLONE-AND-WRITE-PLAY.md`](docs/HOWTO-CLONE-AND-WRITE-PLAY.md)（含 Counter 示例域）。版本节奏见 [`docs/VERSIONING.md`](docs/VERSIONING.md)。

## 架构一览

五层同心结构，依赖只向内（详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)）：

```
L0 core-domain   功能域：玩法规则 + 领域模型 + 端口(Port) + 自有 EventBus(域间解耦)   ← 玩法只写这里
L1 core-runtime / core-server / core-client / protocol            框架编排 / 端逻辑 / 跨端协议
L2 platform-spi  平台抽象 SPI + PlatformProvider + FeatureGate     平台可插拔的唯一机制
L3 平台胶水：Bukkit 为根多模块；兼容车道经 includeBuild，Gradle 不兼容车道用自有 wrapper + 受控 JAR
     各版本内 common / server / client（或仅 server）分目录，平台只做胶水
L4 版本差异只在「该版本构建」内，禁止单工程多源集堆多版本
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
- `examples/counter`：FR-18 上手示例域（纯 L0 加入计数，非产品玩法）。
- `docs/`：规格与决策文档。`.claude/rules/`：防漂移红线。

## 文档导航

- 上手：[`docs/HOWTO-CLONE-AND-WRITE-PLAY.md`](docs/HOWTO-CLONE-AND-WRITE-PLAY.md)
- 版本节奏：[`docs/VERSIONING.md`](docs/VERSIONING.md)
- 需求：[`docs/PRD.md`](docs/PRD.md)
- 架构：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- 接口：[`docs/API.md`](docs/API.md)
- 运维：[`docs/OPERATIONS.md`](docs/OPERATIONS.md)
- 安全：[`SECURITY.md`](SECURITY.md)
- 决策：[`docs/adr/`](docs/adr/)
- 演进与维护：[`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md)
- 变更史：[`CHANGELOG.md`](CHANGELOG.md)

## 快速开始

P1/P2 已交付，P3 以 [`docs/PRD.md`](docs/PRD.md) 的 FR 状态为准。根 wrapper 为 Gradle **9.6.1**；L0–L2 编译为 Java 8 字节码，26.2 三车道须准备 Java 25。

> 当前上游 `mc-testkit` plugin marker 不可用时，根构建仅会从本机 Maven 缓存回退解析该插件及实现模块；首次配置失败时，先按 [`docs/OPERATIONS.md`](docs/OPERATIONS.md) 准备对应本地制品。该临时前提不等于冷缓存或新机器验证已通过。

**物理布局（不拍平到仓库根）**：

```
core/          domain runtime server client paths config protocol spi
platform/
  bukkit/      api common modern 1.12.2 1.20.1 1.21.1 26.2
  fabric/      api 1.20.1 1.21.1 26.2
  forge/       api 1.12.2 1.20.1 1.21.1 26.2
  neoforge/    api 1.20.2
  sponge/      api 1.20.1
modules/       smoke acceptance
```

```bash
# 可发布 jar 聚合到 build/dist/{bukkit,fabric,forge,neoforge,sponge}/
./gradlew :collectReleaseArtifacts

# Bukkit
./gradlew :platform:bukkit:1.20.1:shadowJar
./gradlew :platform:bukkit:1.12.2:shadowJar
./gradlew :platform:bukkit:1.21.1:shadowJar
# Fabric / Forge（-p 指向物理目录；跨代 Forge 用自有 wrapper）
./gradlew -p platform/fabric/1.20.1 remapJar
./gradlew -p platform/fabric/1.21.1 remapJar
./gradlew -p platform/forge/1.20.1 reobfShadowJar

# 26.2（P3 在制车道；Java 25）
./gradlew :platform:bukkit:26.2:shadowJar
./gradlew :buildFabric262
./platform/forge/26.2/gradlew --no-daemon packageArtifacts
```

26.2 的三个命令只构建车道；Paper、Fabric、Forge 的同轮 R7 报告与 `./gradlew :runP3R7Gate` 已在本地通过。该证据不等于 P3 交付：仍须用户第三期实机确认、FR-18 干净克隆复现与 `v0.3.0` 对外 Release。产物放入对应服务端 `plugins/` 或双端 `mods/`；运维见 [`docs/OPERATIONS.md`](docs/OPERATIONS.md)。

### 克隆后换名（脚手架）

```bash
./gradlew renameScaffold \
  -Pmpmt.scaffold.id=mygame \
  -Pmpmt.scaffold.group=com.example.mygame \
  -Pmpmt.scaffold.name=MyGame \
  -Pmpmt.scaffold.dryRun=true
```

详见 [`tools/README.md`](tools/README.md)。纯 kts 实现，无需 python。

## 约定

贡献 / 提交 / 分支 / 文档同步约定见 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) 与 [`.claude/rules/`](.claude/rules/)。提交信息中文、遵循 Conventional Commits。

## 许可

[MIT](LICENSE)。
