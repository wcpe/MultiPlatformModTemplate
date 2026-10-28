# ADR-0037：构建性能调优（实测归因与取舍）

## 状态

已接受（2026-10-28）。

## 背景

全量构建 `:buildAll` 是每轮 CI 与发版的主时长。此前没有任何可复现的耗时归因，
优化只能靠猜。本次用 Gradle 官方 `--profile`、JFR（`jdk.ExecutionSample`）、
async-profiler 与 `gradle-profiler` 实测，得到以下基线（本机 40 核 / 251G / JDK 25）：

```
./gradlew --no-daemon --no-watch-fs --console=plain --profile :buildAll --rerun-tasks
→ 5m27s / 712 actionable tasks
```

叶子任务 2546 个（实际执行 753 个），累计 CPU 时长 **124.6 分**，墙钟 327s——
**平均并发只有 22.9x，40 核没跑满**。这就是“任务不是不多，而是没有真的并行”。

按类别聚合（CPU 秒）：

| 类别 | 累计秒 | 占比 |
|---|---|---|
| SpotBugs（字节码缺陷） | 1664 | 22.2% |
| loom（MC 派生 / 重映射） | 1469 | 19.6% |
| Test（JUnit） | 787 | 10.5% |
| 编译 | 772 | 10.3% |
| PMD | 754 | 10.1% |
| Checkstyle | 715 | 9.6% |
| ktlint | 524 | 7.0% |
| 打包 | 448 | 6.0% |
| JaCoCo | 213 | 2.8% |

静态分析合计 **3834s = 63.9 分 = 51.3% 的全构建工作量**——不是“代码多”，而是
每个分析任务都要 fork 一个 JVM 并加载整套框架；单任务固定开销远大于分析本身：

| 任务 | 数量 | 累计 | 平均/个 |
|---|---|---|---|
| `spotbugsMain` | 41 | 1664s | 40.6s |
| `runKtlintCheckOverKotlinScripts` | 41 | 433s | 10.6s |
| `pmdMain` | 40 | 411s | 10.3s |
| `checkstyleMain` | 42 | 360s | 8.6s |

关键观察：`runKtlintCheckOverKotlinScripts` 共 41 个、累计 433s，而全仓
`*.gradle.kts` 只有 55 个文件——**每个任务只扫自己目录下那 1 个脚本**，
却在为“启动一个内嵌 Kotlin 编译器的进程”付钱。

另一个关键观察：配置阶段（不含任务执行）

| 场景 | 墙钟 |
|---|---|
| 无配置缓存 | 35s |
| 配置缓存冷 | 31s |
| 配置缓存热 | 1s |

且仓内 settings.gradle.kts 已按 Isolated Projects 口径完整改造（无 `allprojects` /
`subprojects` 跨工程模型访问，跨工程数据全走 `gradle.ext` 普通数据，
WCPE Loom 亦已做 IP 适配），但 **IP 从未启用**。

## 决策

1. **启用 Isolated Projects**（`org.gradle.unsafe.isolated-projects=true`），并修复它暴露的
   真实并发缺陷：settings 里三张共享登记表由 `mutableSetOf` / `mutableListOf` /
   `mutableMapOf` 改为 `ConcurrentHashMap.newKeySet` / `CopyOnWriteArrayList` /
   `ConcurrentHashMap`。
2. **CI 的 loom 缓存补上各车道 `build/loom-cache`**（不只 `~/.gradle/caches/fabric-loom`）。
3. **CI 并发度按 runner 实际核数自适应**（上限 16），不再写死 4。
4. **分析任务启动 JVM 统一收归默认 JDK 17**，各车道不再各自抬到 21 / 25。
5. **`org.gradle.vfs.watch=false`**：本仓无长驻 watch 场景，CI 与全量构建本来都传 `--no-watch-fs`，
   现统一到默认值。

**明确不采纳的项**（都有实测依据，见下方备选方案与「理由 5」）：

- 抬高 `-Xmx`、固定 G1、调 region 尺寸；
- 给 `org.gradle.workers.max` 加硬上限；
- 把 ktlint 脚本检查收到根工程；
- 降 SpotBugs `effort`；
- 去掉 `shadowJar` 的 `forceRerun`。

## 理由

### 1. Isolated Projects（配置阶段 −37%）

`--dry-run`（只算任务图）实测（同一台机器、只切 IP 开关）：

| | 无 IP | 启用 IP |
|---|---|---|
| `buildAll` 配置（`--dry-run`） | 35s | **21~22s** |
| `buildAll` 配置（`--dry-run`，负载机交替 A/B） | 66s / 86s | **49s / 19s** |
| 配置缓存冷启动（`help`） | 31s | **14s** |

两种负载条件下都是 IP 更快：空闲机 −37%，负载机交替两轮 −26% / −78%。
方向一致且差距显著，不是噪声。

复现命令（必须先清掉配置缓存，否则直接命中、不会真的配置）：

```bash
rm -rf .gradle/configuration-cache
./gradlew -Dorg.gradle.unsafe.isolated-projects=true  --dry-run :buildAll
./gradlew -Dorg.gradle.unsafe.isolated-projects=false --dry-run :buildAll
```

> ⚠ 测量前提：开发者机器不是空闲机（实测 load average 经常 70~100+）。
> 墙钟时间在负载下不可比，因此本文只采信**配置阶段**（基本单线程）与
> **产物确定性**（SHA-256）这两类不受负载噪声影响的指标；任务级耗时只用
> `--profile` 的**累计 CPU 秒**做占比归因，不用绝对值做前后对比。

IP 让各工程可**并行配置**，直接压缩了配置阶段的关键路径。仓内早已按 IP 口径写好，
不启用只是白白放弃这份投资。

**它同时裸露出了一个真实的数据竞争**：三张共享登记表在 `beforeProject` / `afterEvaluate`
里被多个工程并发写，`LinkedHashMap` 在扩容窗口内会让内部链表与 `size` 字段脱节，
配置缓存序列化阶段报：

```
The map size() is 12, but 1 entries were available when iterating over it.
The map is likely broken or corrupted because of a data race.
```

这不是启用 IP 引入的 bug——无 IP 时只是因为没有并行配置而**没有机会表现出来**。
IP 把隐性缺陷变成了必现失败，修掉它才真正安全。

### 2. loom 缓存补 `build/loom-cache`（CI 上 ~1200s 固定开销）

`remapMinecraftNamedMerged` / `generateForgePrePatchJar` 能 `UP-TO-DATE`，也有
build cache key，但 **CI 上工作区 `build/` 是空的**，于是每次运行都要重新派生：

| 任务 | 耗时 |
|---|---|
| `forge-1.12.2:remapMinecraftNamedMerged` | 166s |
| `neoforge-1.20.2:generateForgePrePatchJar` | 156s |
| `forge-1.21.1:generateForgePrePatchJar` | 154s |
| `fabric-1.21.1:remapMinecraftNamedMerged` | 151s |
| `forge-1.20.1:remapMinecraftNamedMerged` | 147s |
| `fabric-1.20.1:remapMinecraftNamedMerged` | 146s |
| `fabric-1.21.1:remapMinecraftIntermediaryMerged` | 145s |
| `fabric-1.20.1:remapMinecraftIntermediaryMerged` | 142s |

这 8 个任务合计 **~1200s**（并行后仍占关键路径一大块）。而全部车道的
`build/loom-cache` 合计只有 **4.5MB**（对比 `~/.gradle/caches/fabric-loom` 的 4.2GB），
加入缓存成本极低。loom 对两层都做内容校验，同时恢复不会出现“jar 在但哈希不符”的错配。

> 为什么不用 Gradle build cache 代替：`remapMinecraft*` 虽然能生成 cache key，
> 但其输出是写出到 loom 自己的 maven 目录，而不是标准 `@OutputFile`；
> 跨机器时 build cache 无法完整重建这层，直接缓存目录是可靠的做法。

### 3. CI 并发度自适应

历史演进是 `--max-workers=1`（25m05s）→ 固定 4（19m18s）→ 不限（6m46s，40 核）。
固定 4 与 runner 类型耦合：标准 `ubuntu-latest` 是 4 核刚好，但 larger runner /
自托管机可能是 8/16 核，固定 4 就把另一半资源浪费掉。上限 16 的理由是
runner 通常 16GB 内存，而单个 worker 峰值可到 0.8~1.2GB（javac / SpotBugs / 测试各一份）。

### 4. 分析 JVM 统一为 17（减少 worker daemon 批次）

曾有 3 条 26.2 车道抬到 25、2 条抬到 21，其余默认 17——**三种分析 JVM**。
Gradle 的 Worker Daemon 按 JVM 参数分组复用，参数不同就是另一批进程。

关键事实是**分析工具运行 JVM 与被测字节码的目标版本无关**：
Checkstyle 10.x 只要 JDK 11+，PMD 7.x 要 JDK 17+，两者都以**源码**为输入。
车道目标是 25 并不意味着分析 JVM 也要 25。

### 5. 守护进程参数：实测后**不动**

这一项是本次评估里最值得记下的“**做了测量，发现不需要改**”的例子。

初稿曾把 `-Xmx` 从 4g 抬到 6g、显式固定 G1 并加 `ParallelRefProcEnabled` /
`G1HeapRegionSize=8m`，理由是“并发 40 个 worker + 多份 MC 类图，4g 下 GC 密集”。
用 JFR（`jdk.GCHeapSummary` / `jdk.GCPhasePause`）实测后这个理由被完全推翻：

```
配置阶段全程：堆使用 < 100MB，committed 仅 2.0GB
最长 GC 停顿：71.6ms（其余普遍 3~18ms）
```

4g 对这类负载**完全够用**，抬高上限与调整 region 尺寸没有任何可测收益，
只会让守护进程更倾向堆大对象、把实际占用抬高。因此全部撤回，保留原 `-Xmx4g`。

同理撤回 `org.gradle.workers.max=38`：初稿声称“留 2 核给 loom 内部线程池能提高并行度”，
但本次**没有做“加/不加上限”的 A/B**（开发者机器负载 70~100+，墙钟数字不可比），
没有证据就不该改动调度策略——尤其这类参数一旦调错，会让 CI 上任务排队
而本地却有“变快了”的错觉。

### 6. worker 横跨 4 个 JDK：一个真实的并行度瓶颈（部分缓解）

buildAll 运行中 `ps` 统计到 **70 个 `GradleWorkerMain` 进程，常驻 18.0GB**，
横跨 4 个 JDK：

| JDK | 用途 |
|---|---|
| 8 | L0–L2 模块的工具链目标（javac / 测试） |
| 17 | 分析工具（Checkstyle / PMD，本 ADR 决策 4 统一后的结果） |
| 21 | `build-logic` 两个插件工程 |
| 25 | 守护 JVM + 26.2 车道 + SpotBugs worker（默认跟随守护） |

这解释了“40 核平均并发只有 22.9x”：不是任务图不能并行，而是大量进程在抢
内存带宽与调度。决策 4 把分析 JVM 从 3 种收成 1 种，是直接针对这一点的。

进一步合并 JDK 8 需把 L0–L2 改成 `--release 8` 编译，ADR-0004 已明确否决
（工具链比 `--release` 更强，它连 API 面都是 JDK 8）。因此这一层不再往下压。

## 后果

**正向**：

- 配置阶段 −37%（35s → 22s），配置缓存冷启动 −55%（31s → 14s）。
- **13 个发布产物 SHA-256 逐字节一致**（已实测对照），无语义变化。
- 并发登记表的并发缺陷被修掉——不仅服务于性能，也是一个真实正确性修复。
- CI 上省去 8 个重映射任务的重复派生（~1200s 串行等价量）。

**约束与代价**：

- `org.gradle.unsafe.isolated-projects` 仍标 incubating。
  新增跨工程访问（如根工程读 `subprojects[i].xxx`）会直接报错——这本来就是本仓既定的口径，
  但会让“随手写一下”的脚本付出改造代价。
- IP 下的构造函数注入与类加载行为与无 IP 略有差异，首次启用时应重跑一次全量
  （本次已跑，产物一致）。

## 备选方案

**把 ktlint 脚本检查统一收到根工程（未采用）**

41 个任务各扫 1 个脚本、累计 433s，看起来是纯粹的浪费。但实测发现**根工程未应用
`build-conventions.quality`**，因此没有 `runKtlintCheckOverKotlinScripts` 任务。
只在根保留意味着多出“给根工程接入质量插件”的改造，而根工程无 Java 源、
JaCoCo/SpotBugs 对它无意义，会引入一批空转任务与额外的门禁语义差异。
在未找到既保留覆盖又不引入新复杂度的做法前，保留现状。

**把 SpotBugs `effort` 从 MAX 降到 DEFAULT（未采用）**

SpotBugs 是最大单笔（1664s / 22.2%）。但 `effort=MAX` 是刻意的质量取舍
（`static-analysis.md` 明确写了），且已有先例把非 main 源集整批跳过。
降低 effort 会真真实实地弱化缺陷检测能力，不属于“性能优化”范围。
若要再压缩，优先级应是**进一步缩小分析源集**，而不是降低分析深度。

**把 `shadowJar` 的 `forceRerun` 去掉以启用 UP-TO-DATE（未采用）**

代码注释写明“shadow 不把 relocate/exclude 等配置纳入增量指纹”，
去掉后会出现改动 relocate 配置但任务判为 UP-TO-DATE 的静默错误。
打包只占 6%（448s），不值得拿产物正确性换。

**启用 Gradle 的 remote build cache（未采用）**

当前本地 `~/.gradle/caches/build-cache-1` 已 114G。remote cache 需额外基础设施
（Develocity 或自建 HTTP cache），且本仓的 CI 已有 `gradle/actions/setup-gradle`
提供的跨 run 缓存，边际收益不明显。待 loom 层缓存稳定后再评估。
