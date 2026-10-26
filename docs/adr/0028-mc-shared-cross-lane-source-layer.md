# ADR-0028：mc-shared 跨 lane MC 源码共享层 + 功能域模块化

## 状态

提议中（待用户审批）

> 本 ADR 取代 [ADR-0015](0015-domain-organization.md) 的**决策 1**（域初期为 `core-domain` 内的包）与**决策 4**（包→模块成长、不预建域模块），以及其备选方案中「重 per-domain 模块结构 —— 否决」这一条。ADR-0015 的其余内容（域模板组成、依赖规则、注册方式、平台相关下沉）继续有效。

## 背景

项目当前有四类代码，但只支持其中三种共享形态：

| # | 形态 | 位置 | 编译方式 | 生效范围 |
|---|---|---|---|---|
| ① | 零 MC 模块 | `core/*` | 预编译 Java 8 jar | 全端 × 全版本 × 全 loader |
| ② | 低 API 边界 | `platform/bukkit/common` | 预编译（1.12 API） | bukkit 各版本 |
| ③ | loader 内现代共享 | `platform/bukkit/modern` | 预编译 | bukkit 现代版本 |

**缺第四种：碰 MC、但可跨 lane 复用的源码。** 它无法用前三者替代——引用的 API 只存在于特定 MC 版本，无法预编译成通用 jar，只能在目标版本下源码级编译。

实测重复规模（同一模块路径下的同名文件内容比对）：

| 平台 | 版本组 | 同名文件 | 内容完全一致 | 其中 main | 其中 test |
|---|---|---:|---:|---:|---:|
| fabric | 1.20.1 / 1.21.1 / 26.2 | 68 | **33** | **17**（764 行/份） | 16 |
| forge | 1.21.1 / 26.2 | ~40 | 抽查多数一致 | — | — |

fabric 那 17 个 main 文件三版合计 2,292 行，收敛后省 1,528 行；且它们的语义与 MC 版本无关（`FabricPlayerPort` / `FabricWorldPort` / `FabricSchedulerPort` / `FabricMessagePort` / `FabricPersistencePort` 等端口实现），重复纯属结构原因。

同时，功能域目前是 `core/domain` 内的包（ADR-0015 决策 1），导致两个问题：

1. **包名不一致**：内核在 `top.wcpe.mc.mpmt.core.domain.*`，而唯一的示例域在 `top.wcpe.mc.mpmt.domain.capability`（无 `core` 段），两套约定混用。
2. **域间隔离靠包名自律**：域可以 import 兄弟域而构建不会失败。

另外，`.claude/rules/architecture-invariants.md` 的规则很完整，但**没有机械化守卫**——违反只靠人和 AI 自觉。引入一个「碰 MC 的共享层」后，它极易退化为「什么都能放的垃圾桶」。

## 决策

### D1 新增顶层 `mc-shared/` 源码树，承载「碰 MC、跨 lane 复用」的代码

```
mc-shared/                                     顶层源码树
├── README.md                                  约定文档
├── common/src/{main,test}/java/top/wcpe/mc/mpmt/mcshared/common/
├── client/src/{main,test}/java/top/wcpe/mc/mpmt/mcshared/client/
├── server/src/{main,test}/java/top/wcpe/mc/mpmt/mcshared/server/
├── gensrc/<range>/{common,client,server}/src/main/java/
└── mappers/{common,client,server}/to-<range>.txt
```

**硬约束：`mc-shared/` 是源码树，不是 Gradle 模块。** 它不得有 `build.gradle.kts`、不得 include 进 `settings.gradle.kts`。它只被各 lane 的 `setSrcDirs` 在编译期合并，各 lane 用自己的 MC API 各编一次。

### D2 端按源目录分割，禁止包分割 + exclude

`common/` `client/` `server/` 是三个独立源目录，各有自己的 `src/main` 与 `src/test`。

挂载规则（**纯加法**）：

| lane 类型 | 挂载 |
|---|---|
| 纯服务端（bukkit ×4、sponge ×1） | `common/src/main/java` + `server/src/main/java` |
| 双端（fabric ×3、forge ×4、neoforge ×1） | `common/src/main/java` + `client/src/main/java` + `server/src/main/java` |

`test` 源集按同样端过滤。`gensrc/<range>/` 命中的端目录同样直挂。

**理由：加法失败可见，减法失败静默。** 若用「整目录 srcDir + exclude 掉不需要的端」，exclude 漏写不会报错——不该进 jar 的代码会被静默编译进去；而源目录分割漏挂会直接 `cannot find symbol`。

**能力边界（必须明确）**：源目录分割解决「挂载正确性」，**不解决编译期端隔离**。lane 的 `main` 仍是 common + server + client 合并的单一源集，`client` 引用 `server` 的类依然编得过。真正的编译期端隔离需要 lane 拆多源集，代价高且与 loom 单源集假设冲突，本次不做；以守卫扫描 `mc-shared/server/**` 与 `mc-shared/common/**` 是否 import `mcshared.client.*` 作为补充。

### D3 版本特化放 `gensrc/<range>/`，直挂真源

目录名即版本区间：`1.12`、`1.20-1.21.4`、`1.21.5+`。lane 按自身 `minecraftVersion` 与区间比对，命中才挂。

`gensrc` 里的代码是为特定版本写的，**直挂真源，不做文本替换**。

### D4 符号映射放 `mappers/<端>/to-<range>.txt`，`common/` 作用于全部端

一行一条 `<from> <to>`，`#` 起注释：

```
# to-1.21.5+.txt
ResourceLocation Identifier
```

- `mappers/common/to-<range>.txt` 的规则**作用于 common + client + server 三份源码**
- `mappers/client/…` 只作用于 client 源码，`mappers/server/…` 只作用于 server 源码
- 文件按目标区间命名，**内容是累积的完整规则集**，不做链式串联（规则量小时可读性优先）

**仅在需要替换时才复制**：lane 的 MC 版本等于基线版本 → 直挂真源（IDE 跳转与增量编译正常）；大于基线 → 复制到 `build/generated/mc-shared/<端>` 并在副本上应用映射。禁用 `outputs.upToDateWhen { false }`，改写用 `Sync` + `filter` 以保住增量编译与构建缓存。

命名组约束（承 [ADR-0022](0022-unobfuscated-minecraft-naming-policy.md)）：`mcp`（≤1.12.2）/ `mojmap`（1.20.1~1.21.1）/ `unobfuscated`（26.2）。跨命名组的切片不得互相命中；`common/` 切片要求所有命中 lane 的命名组一致。

### D5 功能域按 Gradle 模块切，内核独立为 `core/kernel`

```
core/
├── kernel/                    top.wcpe.mc.mpmt.kernel.*        （原 core/domain，包重命名）
└── domains/
    └── capability/            top.wcpe.mc.mpmt.domain.capability
```

- `core/domain` → `core/kernel`，包 `top.wcpe.mc.mpmt.core.domain.*` → `top.wcpe.mc.mpmt.kernel.*`
- 每个功能域一个 Gradle 模块，位于 `core/domains/<域>`，Gradle 路径 `:core:domains:<域>`
- 域模块包名保持 `top.wcpe.mc.mpmt.domain.<域>`
- 域模块**只依赖 `:core:kernel`**，不得依赖 runtime / server / client / protocol / config / paths / spi
- 域之间零依赖，跨域协作只经 EventBus（[ADR-0011](0011-eventbus-domain-decoupling.md) 不变）

### D6 域经 `META-INF/services` 自动发现，且 `Feature` 契约下沉到内核

`FeatureRegistry` 增加 `discover(ClassLoader)`，用 `ServiceLoader` 加载 `Feature`；各域模块提供 `META-INF/services/top.wcpe.mc.mpmt.kernel.feature.Feature`。各 lane bootstrap 的域注册从多行硬编码改为一行 `discover(...)`。

**`Feature` 与 `RuntimeContext` 契约置于 `core:kernel`（`top.wcpe.mc.mpmt.kernel.feature`），不在 `core:runtime`。** 这是 D5「域只依赖 kernel」的必然要求：若契约留在 L1 的 runtime，域实现 `Feature` 就必须依赖 L1，依赖方向反转（L0 → L1），与 [ADR-0001](0001-layered-architecture.md) 冲突。契约本身只含方法签名（`name` / `onEnable` / `onDisable`、`eventBus()` / `port(Class)`），不含实现，属内核可承载的抽象；`FeatureRegistry` 与 `MpmtRuntime`（生命周期编排）仍留在 L1。

配套：构建层提供 `mpmtDomains()`，让 lane 自动依赖全部 `:core:domains:*` 模块——新增域不需要改任何 lane 脚本。

### D7 架构规则机械化

> **落地状态（已由 [ADR-0036](0036-architecture-guard-structural-only.md) 收窄）**：D7 的目标「把架构规则变成可失败门禁」不变，
> 但实现范围已调整——`build-conventions.architecture` 现在只保留结构性检查（`mc-shared` 形态、顶层语义），
> 判据改为文件系统事实与 `Project.getPath()`；原有的源码文本扫描、`SourceMasker`、孤儿检查与域依赖方向检查
> 已删除，分层与依赖方向的约束改由依赖声明本身与 ArchUnit（`LayeringArchTest` / `DomainArchTest`）承担。
> 原因见 ADR-0036。以下为 D7 原始决策正文，保留作历史记录。

新增 `build-conventions.architecture` 插件，把 `.claude/rules/architecture-invariants.md` 的规则变成可失败的门禁：

- **三档**：`STRICT`（`core/*`：禁 MC + 禁 loader + 禁平台 SDK）/ `MC_AWARE`（`mc-shared/*`：允许 MC，禁 loader 与平台 SDK）/ 无档（`platform/**`：全允许）
- **三类违规**：`PLATFORM_REFERENCE`（import）/ `PLATFORM_NAMING`（包名、类名、反射字符串）/ `BUILD_DEPENDENCY`（构建依赖）
- **`SourceMasker`**：扫描前剥掉注释与字符串字面量，避免文档说明与测试样例造成误报
- **自动发现 + 显式登记**：按目录名发现受管目录，未登记即报错（防「改名躲避守卫」）
- **孤儿检查**：未被任何活跃 lane 命中的 `mc-shared` 目录报错
- **顶层语义校验**：`settings.gradle.kts` 的每个 include 必须落在 `core` / `mc-shared` / `platform` / `modules` / `examples` / `e2e` 之一
- **`mc-shared` 形态校验**：`mc-shared/` 下出现 `build.gradle.kts` 即报错

### D8 版本范围锁定

支持范围固定为 **1.12.2 / 1.20.1 / 1.20.2 / 1.21.1 / 26.2**，对应现有 13 个 lane。本 ADR 不新增任何版本；扩大范围须另立 FR + ADR（承 [ARCHITECTURE.md](../../ARCHITECTURE.md) §10.1）。

## 理由

- **共享源码而非产物是唯一可行形态**。跨版本、跨命名组时不存在可复用的二进制产物；只共享源码、各 lane 各自编译，才能让每侧命名空间与 API 版本各自正确，且不引入跨 lane 构建顺序依赖。
- **按端分源目录而非分包，是因为失败模式不同**。加法漏挂立即编译失败；减法漏 exclude 会静默把不该有的代码编进产物。选可见失败。
- **域升级为模块是为硬隔离**。包名自律在域数量增长后必然失守；模块边界由 Gradle 保证，且域变多后仍能按需独立演进。ADR-0015 的「不预建空壳」纪律继续有效——模板只带 `capability` 一个真实示例域，`examples/counter` 作为可复制模板而不进 `core/domains`。
- **`Feature` 自动发现是域模块化的前提**。现行硬编码注册意味着每加一个域要改 13 个 lane 的 bootstrap，模块化会从「隔离」变成「每加域动 13 处」，与初衷相反。
- **守卫机械化来自同类项目的实证**。`SourceMasker`（剥注释与字符串后再扫）与「未登记即报错」是踩过坑的做法；后者能防住「换个目录名绕开守卫」这种退化路径。
- **`mappers/common` 作用于全部端**，是因为多数改名（如 `ResourceLocation` → `Identifier`）与端无关；若按端各写一遍，同一条规则要维护三份。

## 后果

**正面**

- 消除同 loader 跨版本、跨 loader 同版本的 MC 代码重复（fabric 实测可省 1,528 行，forge 同构文件另计）。
- 新增 lane 不再需要复制业务代码，只需构建参数；新增功能域只需新建模块 + services 登记。
- 架构红线从文档升级为可失败门禁；`mc-shared` 不会退化成无归属的垃圾桶。
- 顶层目录语义唯一，避免出现「`platform/` 下混入共享层」这类漂移。

**负面 / 约束**

- **改包名会改产物字节**：`core.domain.*` → `kernel.*` 与 forge 包结构统一都会改变 class 名。ADR-0027 约定的「13 个发布 jar 的名称与字节」属不可变契约，须同步更新基线哈希并重跑全部验收报告。
- 同一份 `mc-shared` 源码被多个 lane 各编一次，构建时间与编译错误重复上报。
- 非基线 lane 的源码来自 `build/generated/`，IDE 跳转指向副本；基线 lane 保持直挂真源以缓解。
- `mc-shared` 自身成为跨 lane 协作热点：两条 lane 同时改同一文件即冲突。
- `mc-shared` 需要 Java 17（与 lane 一致），不能沿用核心层的 Java 8 约定；因此 `mc-shared` 的代码**不能**被 `core/*` 依赖，方向只能是 lane → mc-shared。
- 编译期端隔离仍未实现（见 D2 能力边界），依赖守卫扫描作为补偿。

## 备选方案

| 方案 | 落选原因 |
|---|---|
| **维持现状**（各 lane 各存一份） | 已产生实质重复（fabric 33 个文件三版一致）；新增版本成本按版本数倍增 |
| **`platform/slices/`**（共享层挂在 `platform/` 下） | `platform/` 的语义是「平台车道」，混入共享层会造成语义污染，且易被误认为又一个平台 |
| **一个 Gradle 模块产出可复用 jar** | 跨版本与跨命名组时不存在通用产物，API 被钉死在单一版本，机制不成立 |
| **包名分割 client/server**（整目录 srcDir + exclude） | exclude 漏写会静默编错，失败不可见 |
| **每域一份 mc-shared**（`core/domains/<域>/mc-shared/`） | 跨域通用代码无归属；版本切片按域碎片化；lane 挂载需遍历所有域，冲突面随域数线性增长 |
| **域继续作为包**（维持 ADR-0015 决策 1/4） | 域间隔离靠自律；包名已出现 `core.domain.*` 与 `domain.*` 两套不一致约定 |
| **统一转成 Kotlin 源集变体 / 条件编译** | 项目以 Java 为主，条件编译的收益区间窄且污染源码与 IDE 体验；当前差异以改名与整文件替换为主，切片 + 映射已足够 |
| **一次性把共享层做进 `core`** | `core` 严格 Java 8 且零 MC；混入 MC 引用会破坏其可独立编译与预览器消费的能力 |
