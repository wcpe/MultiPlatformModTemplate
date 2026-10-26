# ADR-0036：架构守卫只做结构性检查，分层约束交给依赖声明与 ArchUnit

## 状态

已接受（收窄 [ADR-0028](0028-mc-shared-cross-lane-source-layer.md) 决策 D7 的实现范围；D7 的目标「架构规则机械化」不变，改变的是落点）

## 背景

[ADR-0028](0028-mc-shared-cross-lane-source-layer.md) 决策 D7 让 `build-conventions.architecture` 把架构规则变成可失败门禁，其中主要手段是**扫描 `.java` 源码文本**：按包名前缀匹配 `import` 与全限定类名，分 `STRICT` / `MC_AWARE` 两档，并配 `SourceMasker` 剥注释与字符串字面量以避免误报。

这条路线在落地后暴露三个问题。

**一、与 ArchUnit 重复，且更弱。**

同一批约束在 ArchUnit 里已有更可靠的落点：

- `core/kernel` 的 `LayeringArchTest` 断言 L0 内核不依赖 `net.minecraft..` / `org.bukkit..` / `net.fabricmc..` 等平台包；
- 域模块的 `DomainArchTest` 用 `slices()` 断言域间不互依、依赖图无环。

ArchUnit 在**字节码**层面建类图再断言，不受源码写法影响。文本扫描则天然不可靠：通配 `import a.b.*` 无法判断实际用到哪个类型、多行 `import` 拼接可能漏匹配、注释与字符串需要额外脱敏才不误报。两者验同一件事时，文本扫描没有任何优势，只有额外维护成本与误报/漏报风险。

**二、结构性检查的实现方式本身是错的。**

`checkTopLevelSemantics` 用正则 `include\(\s*"([^"]+)"\s*\)` 解析 `settings.gradle.kts`，只匹配「单参数且右括号紧邻」的写法。实测该正则在真实 `settings.gradle.kts` 上只命中 1 条（`:e2e:harness`），而文件里有 7 处 `include(`、覆盖 30 余个模块——`core:*` / `examples:*` / `platform:*` / `modules:*` 全部是多参数换行形态，一条都没被检查。配套单测全部使用单参数 fixture（仓库从不使用这种写法），因此测试长期为绿而不具备对应能力。

**三、部分检查是没有输入的空转。**

`checkOrphans` 依赖 Gradle 属性 `mpmt.laneMcVersions`，注释称「放在属性而非硬编码以保持单一真源」，但该属性在 `gradle.properties` 中从未定义，取值永远回落到硬编码的默认列表。`checkDomainModuleDirections` 为绕开配置期求值顺序，改成读取并解析各模块 `build.gradle.kts` 的**正文**——这既落入「测试/工具不得读源码文本做判断」的反模式，又因为只收集 `:core:domains:` 前缀而漏掉 `:examples:`，导致作为范本的 `examples/counter` 完全不受约束。

## 决策

`build-conventions.architecture` 只保留**结构性**检查，且判据必须是结构化事实：

1. **`mc-shared` 形态**：`mc-shared/` 下不得出现 `build.gradle*`。判据是文件系统事实。
2. **顶层语义**：每个已 include 的工程路径必须落在允许的顶层前缀内。判据是 Gradle 自己的 `Project.getPath()`，遍历全部工程，不解析 `settings.gradle.kts` 文本。

删除以下内容：源码文本扫描（含 `ArchitectureTier` 三档与 `SourceMasker`）、孤儿检查及其 `mpmt.laneMcVersions` 属性、域依赖方向检查及其脚本正文解析、以及服务这些实现的测试。

分层与依赖方向的约束改由两处更强的手段承担：**依赖声明本身**（不在 classpath 上就编译失败）与 **ArchUnit**（`LayeringArchTest` / `DomainArchTest`）。

## 理由

- **判据决定可靠性**：工程路径与文件存在性是结构化事实，多参数 `include`、动态 `include(path)`、换行缩进、改名都改变不了它；源码文本不是。
- **避免「有守卫」的错觉**：一个覆盖率 1/30 且测试为绿的守卫，比没有守卫更危险——它让人相信红线被守着。
- **每种约束用最合适的手段**：分层用类型系统与字节码断言，结构约定用文件系统与工程模型，采集数据用结构化接口。用正则扫文本同时承担这三类职责，是这三处缺陷的共同根因。
- **删除的检查确有更强的等价物**：`scanSources` 的两档分别对应 `LayeringArchTest`（L0 内核）与依赖声明（模块 classpath）；`checkDomainModuleDirections` 对应 `DomainArchTest`（域间不依赖、无环）。

## 后果

**正面**

- 守卫从 567 行降到 93 行，判据全部是结构化事实，不再需要 `SourceMasker` 这类为文本扫描兜底的辅助设施。
- 顶层语义检查的覆盖从实测 1 条提升到 55 个工程；`mc-shared` 形态检查经负向实测确认能拦下植入的 `build.gradle.kts`。
- 删除了 290 行解析构建脚本文本的代码，与「不得读源码文本做判断」的反模式保持一致。

**负面与约束**

- `examples/` 下的域**不再有**「域间零依赖」的机检。`DomainArchTest` 的切片范围是 `top.wcpe.mc.mpmt.domain`，不含 `top.wcpe.mc.mpmt.examples`。若要覆盖，应把切片规则扩展到 `examples` 包，或为示例域单独加一条 ArchUnit 规则——**不应**回到读脚本正文的路子。
- 新增顶层目录时，守卫只在**已被 include 之后**才报错；「在 settings 里写下非法 include」这一步不拦截。这是有意的取舍：能跑起来才会被检查，而错误的顶层目录一旦 include 就会在 `verifyArchitecture` 立即失败。
- `mc-shared` 不得被 include 这条红线，现在由「形态检查（无 `build.gradle*`）」间接保证——没有构建脚本就无法被 include。若将来 Gradle 支持无脚本模块，需重新评估。

## 备选方案

- **保留文本扫描，修好它**：需要处理通配 import、多行 import、注释与字符串脱敏、增量文件发现，最终仍弱于 ArchUnit 的字节码分析。投入大于收益，否决。
- **把 `checkTopLevelSemantics` 的正则改成解析整个 `include(...)` 参数列表**：能修好覆盖率，但仍然是解析脚本文本；遍历 `Project.getPath()` 更简单也更准确。否决。
- **补齐 `mpmt.laneMcVersions` 属性，保留孤儿检查**：孤儿检查的初衷（`mc-shared/gensrc/<range>` 与活跃车道对齐）有价值，但当前实现依赖的「活跃车道版本集合」在 Gradle 模型里没有可靠来源，补属性只是把硬编码换个地方。若日后要恢复，应基于 `settings.gradle.kts` 实际 include 的车道工程路径推导，而非新增一个需要人工维护的属性。本次不做。
- **整个删掉守卫，只留 ArchUnit 与依赖声明**：`mc-shared` 不得退化成 Gradle 模块、顶层目录白名单这两条，ArchUnit 与依赖声明都无法表达（前者是文件形态，后者是工程路径集合），故保留。
