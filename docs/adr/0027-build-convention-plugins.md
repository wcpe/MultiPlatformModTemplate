# ADR-0027：构建约定插件分层（车道只做配置）

## 状态

已接受（细化 [ADR-0026](0026-single-build-subproject-unification.md) 之后"平台车道如何组织构建逻辑"的决策；[ADR-0012](0012-packaging-and-dependency-isolation.md) / [ADR-0014](0014-realserver-acceptance-harness.md) / [ADR-0023](0023-p3-r7-automated-release-authority.md) 的打包与验收契约不变）

## 背景

[ADR-0026](0026-single-build-subproject-unification.md) 把 9 条平台车道变成根构建的普通子模块后，"构建拓扑"不再是契约，但**构建逻辑本身仍是复制粘贴**：

- 根 `subprojects {}` 里 124 行的质量工具链装配（checkstyle / PMD / SpotBugs+FindSecBugs / ktlint / detekt / kover / JaCoCo 底线）与 13 份车道脚本里重复出现的同类装配并存，改一处要改 N 处。
- 每条车道各自复制：`verifyPackaging` 断言、API 冻结哈希校验、验收轮次编排（矩阵 / runId / 报告路径 / 五类制品注入）、`sha256` 之类的 helper、静态分析工具版本与排除过滤器。
- 车道脚本膨胀到 240–800 行，其中大部分是"流程"而非"本车道的差异"；评审时无从判断哪一行才是真正的车道语义。
- 契约测试只能断言脚本文本（`build.contains("fun moduleJar(")` 之类），把实现细节固化成契约：任何抽取都会让测试红，而不是让行为回归暴露出来。

## 决策

1. **公共构建流程一律进插件工程**：`build-logic/build-conventions`（构建约定插件，含契约测试）与 `build-logic/realserver-acceptance`（真服验收基础设施，既有）。平台车道脚本只保留"本车道是什么"的参数与差异。
2. **插件按功能分层，id 不含项目身份**：
   - `build-conventions.quality`：质量工具链装配与车道级覆盖（工具版本、分析 JVM）。
   - `build-conventions.platform`：平台车道公共层（验收控制通道常量生成等）。
   - `build-conventions.<loader>`：loader 特有层，当前为 `build-conventions.fabric` / `.bukkit` / `.forge` / `.neoforge` / `.sponge`。
   命名用功能语义、不出现项目名或 `mpmt` 前缀——插件目录、插件 id 与实现类在项目改名后无需改动。
3. **车道差异用扩展属性表达**：插件提供约定默认值（`convention(...)`），车道只在偏离时才写，例如 `quality { }` / `platformLane { }` / `fabricLane { }`。插件在 `afterEvaluate` 读取车道配置值（`plugins {}` 应用早于车道的配置块）。
4. **不可变契约仍留在可见处**：任务名与任务路径、13 个发布 jar 的名称与字节、报告路径与 `SERVER-GAMETEST-REPORT v2` 格式、`-Pmpmt.acceptance.*` 属性名与默认值、真服门禁的判定强度与失败文案、`verifyPackaging` 与冻结校验的断言内容。抽取只是把**实现**搬进插件，契约逐字保留。
5. **契约测试断言行为与单一真源**：断言"车道不再重复装配 X"、"插件单点实现 Y"、"任务仍挂在门禁上"，而不是断言某段脚本文本存在。
6. **插件工程可动态互操作**：loom / shadow / SpongeGradle 等插件类型不进插件工程编译类路径，相关配置以 `withGroovyBuilder` 与按名反射等价表达（`GroovyInterop.kt`、各 loader 的 `*Interop.kt`）。若将来允许给插件工程加 `compileOnly` 依赖，可替换为类型化 API。

## 理由

- **单一真源**：规则集路径、工具版本、报告路径解析、打包断言、验收注入只有一份；插件修一次，全部车道生效。
- **车道脚本回归"配置"**：新增车道或新版本只需声明参数（MC 版本、loader 版本、目标 JDK、L4 选择、报告路径），流程无需复制。
- **契约更贴近行为**：任务图、产物字节、门禁实跑成为回归证据；脚本文本不再是契约，重构不再被测试反向锁死。
- **可验证的等价迁移**：每一步都能用"任务图逐行不变 + 发布 jar 字节不变 + 门禁实跑通过"证伪，重构风险可控。

## 后果

- 车道脚本显著缩短并趋于同构：sponge 286 → 105、neoforge 377 → 90、bukkit 270/328/267/316 → 123/136/126/186、fabric 464/571/655 → 289/326/420、forge 2423 → 1618（forge 1.12.2/1.20.1 的旧版编排与 userdev 镜像属本车道独有配置，压缩有限）。
- 插件工程成为关键路径：改插件必须跑全量验证（根入口任务图 0 差异、13 个发布 jar 字节不变、质量门与真服门实跑），因此插件工程自身也需要可用的 ktlint / detekt 门禁。
- 引入少量动态互操作代码，代价是无法获得外部插件类型的编译期检查；换取插件工程不依赖各 loader 工具链（避免把 loom / SpongeGradle 拉进构建约定插件的类路径）。
- 车道脚本仍可读：留下的每一行都应是"本车道的参数或差异"，流程性内容出现即为设计异味。

## 备选方案

- **继续用根 `subprojects {}` 集中**：无法表达逐 loader 差异（fabric/forge/bukkit 的流程本就不同），结果只能是"公共部分集中 + loader 部分继续复制"，且没有地方承载 loader 级的扩展属性。
- **改用 `buildSrc`**：Gradle 会把它并入主构建的编译，无法独立跑自身的 ktlint / detekt / 测试门禁，也无法给插件工程写契约测试。
- **每个 loader 一个独立插件工程**：基础设施（kotlin-dsl 配置、门禁接线、插件注册）重复五份且版本 pin 分散，收益不抵成本；分层放在同一个插件工程内已能隔离命名空间（`FABRIC_*` / `BUKKIT_*` / `FORGE_*` / `NEOFORGE_*` / `SPONGE_*`）。
