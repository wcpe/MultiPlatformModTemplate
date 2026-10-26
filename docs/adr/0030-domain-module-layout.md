# ADR-0030：功能域按「API / internal / 实现」三段八模块组织

## 状态

已接受（细化 [ADR-0028](0028-mc-shared-cross-lane-source-layer.md) 决策 5「功能域按 Gradle 模块切」；为 [ADR-0015](0015-domain-organization.md) 的「不预建空壳」补充执行口径）

## 背景

[ADR-0028](0028-mc-shared-cross-lane-source-layer.md) 决策 5 只说"功能域按 Gradle 模块切，域只依赖 `:core:kernel`"，没有规定域**内部**怎么分。当前仓里只有一个域 `core:domains:capability`，是单一模块（474 行、3 个类），既装对外契约又装实现，无法回答三个问题：

1. 平台车道要引用某个域的能力时，该引哪个模块？引进来的东西是稳定契约还是可改实现？
2. 客户端与服务端的契约混在一起，服务端车道会不会被迫编译客户端契约？
3. 域内部实现（模型 / 算法 / 编解码）与"必须碰 MC 的共享实现"该怎么分界？

同时发现一处既有破损：`examples/counter` 从 `core:domains:capability` 导入 `PlayerJoinedEvent` / `PlayerLeftEvent`，但它的 `build.gradle.kts` 只声明了 `api(project(":core:kernel"))`，**当前编译不过**。这说明"域之间零依赖"在实践里已经被绕过了一次——因为原先没有"共用契约该放哪"的答案，事件类只能塞进某个具体域，别的域想复用就只能跨域 import。

## 决策

### 1. 域的模块布局（八模块标准）

每个域在 `core/domains/<域>/` 下按用途切成八个 Gradle 子模块：

| 模块 | 定位 | 可见性 | 内容约束 |
|---|---|---|---|
| `common-api` | 对外发布 | `api` | client / server 共用契约（事件、DTO、端口接口） |
| `client-api` | 对外发布 | `api` | 仅客户端契约 |
| `server-api` | 对外发布 | `api` | 仅服务端契约 |
| `core` | 内部逻辑 | `implementation` | 共用实现：模型 / 算法 / 编解码 |
| `client-internal` | 内部接口 | `implementation` | interface + 内部 DTO + 安装点 object（不放实现） |
| `server-internal` | 内部接口 | `implementation` | interface + 内部 DTO + 安装点 object（不放实现） |
| `client` | 内部逻辑 | `implementation` | 客户端实现通用存放地 |
| `server` | 内部逻辑 | `implementation` | 服务端实现 |

全部八模块**零 MC**：不得依赖 `minecraft` / 加载器 API，工具链固定 Java 8。

### 2. 必须碰 MC 的共享实现归 `mc-shared/`

域模块一律零 MC。凡需要引用 `net.minecraft` 的共享实现，放 `mc-shared/` 源码树：

```
mc-shared/
├── client/   仅客户端需要的 MC 实现
└── common/   client 与 server 都需要的 MC 类
```

`mc-shared/` 是**源码树不是 Gradle 模块**（ADR-0028 决策 1 不变）：无 `build.gradle.kts`、不进 `settings.gradle.kts`，由各车道用自己的 MC API 各编一次。

### 3. 空壳执行口径（对 ADR-0015 的补充，不推翻它）

八模块是**标准布局**，不是"每个域必须立刻建齐八个空模块"。落地规则：

- 只创建**当前有真实内容**的模块；没有内容的模块不建。
- 新增模块时必须同时有可编译、可测试的实质代码——不允许只有 `package-info.java` 或 README 占位。
- 标准通过**可复制的范本**固化（见决策 4），后续域照范本按需长出缺失的模块。
- 域确实需要某个空缺模块时，在同一变更里补齐它的真实内容与测试，而不是先建壳再填。

这条口径是 [ADR-0015](0015-domain-organization.md)「不预建空壳」的直接延伸，不构成破例。

### 4. 范本：`examples/counter` 升格为八模块示范

`examples/counter` 从单模块改造成八模块结构的**完整可编译范本**，每个模块都带真实实现与测试，作为新建域的抄写起点。它继续不参与 `:collectReleaseArtifacts` 发布。

范本同时修掉上文那处破损：共用事件契约下沉到 `counter-common-api`，`capability` 域不再被 `counter` 反向依赖。

### 5. 模块依赖方向

```
<域>-common-api  ←─ client-api ──→ client-internal ──┐
     ↑      ←─ server-api ──→ server-internal ──┤
     │      ←─ core ───────────────────────────→ client
     │                                            server
     └───────────── 全部只允许 `:core:kernel` 作为域外依赖
```

- 域外依赖只有 `:core:kernel`（ADR-0028 决策 5 不变）。
- 域之间零依赖（护栏既有红线）。跨域协作只经 EventBus（ADR-0011）；共用契约若要跨域复用，下沉到 `:core:kernel`，不允许域互相 import。
- `-internal` 模块只放 interface 与内部 DTO，不放实现。
- `-client-api` / `-server-api` 不得被对端引用。
- **实现模块禁止横向依赖对端实现**：`client` 不得依赖 `server`，反之亦然。两端若需协作，只能经 `common-api` / `core` / 各自的 `-internal` 抽象，或由平台车道在装配处（组合根）同时持有两侧实现。这一条由架构守卫机检（见决策 6）。

### 6. 依赖方向机检

`build-conventions.architecture` 的 `verifyArchitecture` 包含 `checkDomainModuleDirections`：
从 Gradle 项目模型读取域模块的直接依赖（不解析构建脚本文本），发现实现模块横向依赖对端即时失败。
`-api` / `-internal` / `-core` / `-common-api` 等抽象模块不受方向限制。

配套行为测试覆盖三种情形：横向依赖被拦、依赖本端抽象放行、抽象模块互相依赖放行。

## 理由

- **引用面清晰**：车道引 `-api` 即承诺稳定；引 `-core` / `-client` / `-server` 即接受实现可变。评审时从模块名就能判断耦合强度。
- **端隔离**：服务端车道不需要 client-api，客户端车道不需要 server-api，编译期就不会被对端契约污染。
- **零 MC 可机检**：八模块全部零 MC，可以用一条 classpath 断言统一守住（ADR-0028 决策 7 的 STRICT 档）。MC 实现只可能在 `mc-shared/`，边界天然可查。
- **修掉真实破损**：`counter` 跨域 import 的根因是"共用契约无处安放"。`-common-api` 提供了正确位置。

## 后果

正面：

- 新建域有明确模板，不用每次重新讨论分层。
- `examples/counter` 从"编译不过的示例"变成真正可跑的范本。

负面与约束：

- 模块数量显著增加：一个完整域从 1 个模块变成最多 8 个。构建配置面积变大，`settings.gradle.kts` 的 include 清单变长。
- **`mpmtDomains()` 必须排除隐式父项目**：引入 `<域>/<域>-<后缀>` 嵌套后，`:core:domains:<域>` 本身会变成没有构建脚本、没有变体的隐式父项目。按路径前缀过滤时若把它一并返回，车道解析会报 `No variants exist`。实现已改为只取 `buildFile.isFile` 的叶子模块；这是嵌套布局的必备配套，不是可选项。
- 八模块标准对小域可能过重。判断标准是"该域是否真的有客户端/服务端差异"：纯 L0 域（如 `counter` 的计数逻辑）实际只用到 `-common-api` / `-core` / `-client` / `-server`，不需要 `-client-api` / `-server-api` / 两个 `-internal`。范本必须演示这种"按需长出"的形态，否则会被误读成"必须建齐八个"。
- 现有的 `capability` 域已按新标准迁移为五个模块：`capability-common-api`（域事件）+ `capability-core`（`CapabilityKeys` 等共用逻辑）+ `capability-server-api` / `capability-server-internal` / `capability-server`（Feature 装配入口与服务端实现）。迁移刻意**保持包名 `top.wcpe.mc.mpmt.domain.capability` 不变**，因此引用文件无需改动——只变模块归属，不变 API。客户端侧（`client-api` / `client-internal` / `client`）当前无生产消费者，按决策 3「只创建当前有真实内容的模块」未建，待该域真的长出客户端 HUD 装配时再按需补齐。

## 备选方案

- **保持单模块域**：最简单，但回答不了"契约 vs 实现"的引用面问题，`counter` 那类跨域 import 会继续发生。
- **每个域固定建齐八个模块**：结构与标准完全一致，但与「不预建空壳」直接冲突，且会产生大量空模块，构建配置与 include 清单被稀释。已明确不采用。
- **把"必须碰 MC 的共享实现"也放进域模块**：模块数更少，但域就不再是零 MC，ADR-0028 决策 1 的"源码树各 lane 各编一次"机制失效，跨版本编译能力受损。
