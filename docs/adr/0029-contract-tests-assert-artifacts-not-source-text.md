# ADR-0029：契约测试只断言产物、运行期行为与结构化数据

## 状态

已接受（收紧 [ADR-0027](0027-build-convention-plugins.md) 背景里已识别但未约束的"契约测试断言脚本文本"问题；[ADR-0025](0025-wcpe-loom-toolchain-unification.md) / [ADR-0026](0026-single-build-subproject-unification.md) 的构建拓扑决策本身不变）

## 背景

[ADR-0027](0027-build-convention-plugins.md) 的背景已记录过这个痛点：

> 契约测试只能断言脚本文本（`build.contains("fun moduleJar(")` 之类），把实现细节固化成契约：任何抽取都会让测试红，而不是让行为回归暴露出来。

此后问题继续扩大。整改前四个契约测试共约 1280 行、约 240 条断言，主体形态是 `read(build.gradle.kts)` + `contains("...")`、`read(SomeClass.java)` + `contains("...")`、`Files.exists(源码路径)`。具体危害有三类：

1. **等价改写就变红**：把 `files(任务集合)` 换成非传递项目依赖（语义等价甚至更正确），因为脚本文本里不再有 `fun moduleJar(` 字样而红。
2. **版本升级连锁返工**：`top.wcpe.loom` 升到 1.17.12 时，四处硬编码 `1.17.2` / `1.17.1` 的断言连带失败——它们没在验证任何行为，只是把版本号抄了一遍。
3. **该红的时候不红**：读源码断言无法发现"任务注册了但没接线到任何构建路径"。若断言的是"文案里有 verifyRealServerReportsStrict"，删掉 `mustRunAfter` 接线它依然是绿的。

第 3 点尤其危险：这类断言给出的是**虚假的安全感**，比没有测试更糟。

## 决策

契约测试的断言必须落在以下三类载体之一，禁止读 `.java` / `.gradle.kts` / `.kt` 源码做文本包含判断，禁止断言源码文件路径：

1. **编译产物**：jar 条目、`META-INF` 服务声明与已展开的 `mods.toml`、class 文件魔数与 major 版本、class 常量池中的类名引用 / 字符串常量 / 方法描述符。
2. **运行期行为**：把插件应用到 `ProjectBuilder` 假工程后检查任务注册与属性契约；真正执行任务并断言其失败方式；Gradle 任务图本身。
3. **结构化数据**：构建注入的 `mpmt.test.*` 系统属性、`PlatformLane` / `PlatformLaneCatalog` 之类的枚举与目录模型、JSON 期望值 fixture。

对应的三类处置：

- **删除（零覆盖损失）**：约束已由 Gradle 机制、同类产物断言或系统属性矩阵覆盖的项。
- **转为产物断言**：属于发布物契约且无其他机制保证的项（通道名、API 用法、版本区间、场景名）。
- **转为行为断言**：属于接线/注册/顺序契约的项，改用 `ProjectBuilder` 或真实任务执行验证。

`-Pmpmt.acceptance.*` 属性名、任务名与路径、`SERVER-GAMETEST-REPORT v2` 格式、13 个发布 jar 的名称与字节均**不变**，本决策只改变验证这些契约的观测手段。

## 理由

- **鉴别力**：产物与行为断言只在契约真的被破坏时才红。等价重构不再触发无意义返工。
- **失败可读**：`class 常量池缺少 CustomPacketPayload` 直接指向"这段代码没实现该接口"，而 `build.contains("CustomPacketPayload")` 只说明有人写过这行字。
- **抗漂移**：版本号、插件 id、任务名不再被抄进测试。它们各自只有一个真源。
- **成本可控**：常量池扫描约 50 行、`ProjectBuilder` 无需引入 TestKit 即可用（`gradleTestKit()` 从本地 Gradle 分发解析，不触发联网）。

## 后果

正面：

- 契约测试从"脚本快照"变成真正的回归网。
- 车道脚本与插件实现可自由抽取重组，只要行为不变测试就不动。

负面与约束：

- **部分契约的保证强度从"测试期"降级为"运行期"**：例如真服门的属性缺失、262 三车道的严格校验顺序，现在由真跑门禁失败来暴露，而不是在单测阶段前置拦截。这类契约必须在发布/验收流程里实际执行到，否则等于无保护。发布流程不得跳过 `verifyRealServerReportsStrict` 与各车道 `runRealServerAcceptance`。
- **有一条约束改用结构化属性承载**：根 `pluginManagement` 的 `mavenLocal { }` 只放行 `top.wcpe.mc-testkit` / `top.wcpe.mc` 两个组。Gradle 的仓库内容过滤器（`RepositoryContentDescriptor`）**只写不可读**，没有回读 API，因此无法直接断言"实际放行了哪些组"。处置：允许清单提为 `gradle.properties` 的 `mpmt.localMavenGroups`，`settings.gradle.kts` 只按该属性逐项 `includeGroup`，并由 `RealServerGateContractTest` 对该属性做结构化断言（值必须恰为那两个组、且都落在 `top.wcpe.*` 命名空间）。
  **已被 [ADR-0032](0032-wcpe-loom-local-publish-maven-groups.md) 修订**：为让本机发布的 wcpe-loom 新版本可被解析，清单扩为四项（增 `top.wcpe.loom` / `dev.architectury`），命名空间约束改为「`top.wcpe.*` 或已登记的第三方例外（当前唯一例外是 `dev.architectury`）」。精确集合断言保留，口径见 ADR-0032。
  另加一条配置期硬保护：清单为空时 `settings.gradle.kts` 直接 `check` 失败——不带 `content` 过滤的 `mavenLocal` 会放行本机 m2 的一切制品，比原本的作用域严格更危险，绝不能静默退化成"全开"。
  **残留缺口**：该手段保证"清单值不被放宽"和"空清单不发生"，但不阻止有人在 `settings.gradle.kts` 里绕过属性另写一条 `includeGroup` 字面量。那一层靠配置评审拦截。
- 常量池断言比源码断言更难读，需要在断言消息里写清"这是在验证哪个契约"。
- 新增契约一律按本 ADR 的口径编写；评审时"测试读了源码文本"应视为阻断项。

## 备选方案

- **全量转常量池断言，一条不删**：覆盖最完整，但工作量最大，且部分断言（"用了某个 API"）转成常量池后语义会变得间接，可读性反而下降；对"插件注册了哪个任务"这类接线契约，常量池根本表达不了，仍必须走行为断言。
- **降级护栏，保留既有测试并登记为技术债**：风险最低、零返工，但护栏从此有既有例外，且第 3 类危害（该红不红）会继续存在。
- **引入 Gradle TestKit 全量行为化**：鉴别力最强，但需要真实 Gradle 运行、显著变慢，且当前环境网络受限；`ProjectBuilder` 已覆盖本项目所需的插件接线断言，暂不引入 TestKit。
