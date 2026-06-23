# ADR-0013：线程模型与归属调度——Folia 无主线程，SchedulerPort 按归属调度

## 状态
已接受

## 背景
评审指出本设计最大落地隐患是线程模型："网络收包后切回主线程再碰世界状态"在 **Folia 上不成立**——Folia 区域化多线程、**无单一主线程**，改某实体 / 方块必须调度到拥有它的区域 / 实体线程，跨区域直接访问会触发线程检查异常。原 `SchedulerPort.runSync(Runnable)` 表达不了"针对哪个实体 / 位置"。此外 `TransportPort.onReceive` 与 EventBus 的投递线程语义、客户端跨线程发布机制均未定，会出现"happy path 绿、真机偶发竞态"。

## 决策
1. **SchedulerPort 按归属调度**：`runForEntity(EntityRef, Runnable)` / `runForLocation(WorldRef, x, z, Runnable)` / `runGlobal(Runnable)` / `runAsync(Runnable)`。非 Folia 平台这些归属统一退化为"服务端主线程"；Folia 分别落到 `EntityScheduler` / `RegionScheduler(byLocation)` / `GlobalRegionScheduler`。**L0 触碰世界 / 实体状态必须经带归属的调度入口，禁止无归属 `runSync` 碰世界态。**
2. **`TransportPort.onReceive` 线程契约写死**：约定可能在任意网络线程触发；L1 可靠性层一律按线程安全实现；派发给 L0 碰世界态前经带归属 SchedulerPort 切到正确线程。**收包→归属**：L3 拿到平台连接对象解析出归属（玩家 / 实体），再调度。
3. **EventBus 投递线程契约**：`publish` 在发布者线程同步派发（或入队）；**订阅者不得假设任何特定线程，凡碰世界 / 实体态必须经带归属 SchedulerPort 自行切线程**；明确投递是否 FIFO、是否允许订阅者内重入。
4. **客户端跨线程发布机制**：netty 收包 → 各 loader 的 client `execute` / `enqueueWork` 切到客户端线程 → 原子替换不可变快照的 `volatile` 引用 → 渲染线程只读该 volatile；禁止渲染线程读可变共享字段、禁止在渲染线程改共享状态。
5. **措辞统一**：全文"切回主线程"改为"切回该状态归属的执行线程 / 上下文"。

## 理由
- Folia 区域模型是真实约束，归属调度是几乎所有跨平台插件的返工点，必须在写 L3 之前定死端口。
- 线程契约不定，每个实现者按自己平台直觉假设线程，跨平台必炸。

## 后果
- 正面：Folia 可正确落地；线程模型确定、可测。
- 负面：SchedulerPort 接口更复杂；L0 调用方需显式声明归属。
- 约束（写入 testing-and-quality）：涉及调度 / 线程的端口**不能用"同一份 L0 测试通吃"**，须按平台写契约测试；封禁成功后的踢人副作用须经 `runForEntity` 调度（Folia 下尤甚）。

## 备选方案
- **仅"FeatureGate 选 RegionScheduler"**：无归属信息，Folia 上无法落点——否决（评审）。
- **忽略 Folia / 假定有主线程**：用户要求支持 Folia——否决。
