# ADR-0035：验收插件不 shade 产品侧类，改由产品插件在运行期提供

## 状态

已接受

### 验证结果（2026-10-22 实施）

实施后按 README 的正确姿势跑了完整验收链路
（`ensurePaperRealServerHost` 与 `runAcceptanceClient` 必须在同一个 Gradle 调用里串接，
否则宿主会在构建结束时被 `close()` 停掉），Bukkit 1.20.1 结果：

```
SERVER-GAMETEST-REPORT v2
TOTAL 14  PASS 14  FAIL 0  ERROR 0  SKIP 0
RESULT PASS
```

`runRealServerAcceptance` 门禁：`lane=Bukkit 报告 PASS`、`BUILD SUCCESSFUL`。

对比修复前：报告是 v1 且只有 1 条 `loader constraint violation`——那个异常同时导致了
「ClassLoader 冲突」与「v1 兜底渲染」两个表面症状，本质是同一个根因。

实测数据：验收 jar 由 203 个条目（其中 104 个产品侧类）降到 99 个条目、0 个产品侧类；
`acceptance/*` 保留 46 个、`platform/bukkit` 保留 43 个。

## 背景

Bukkit 车道的真服验收需要两个插件同时部署到 Paper：产品插件
（`mpmt-bukkit-<mc>-<version>.jar`，`plugin.yml` 里 `name: MultiPlatformModTemplate`）与验收插件
（`mpmt-bukkit-acceptance-<mc>-<version>.jar`，`name: mpmt-acceptance`）。

按 README 走完整验收链路时报告判定 FAIL，唯一一条记录是：

```
ERROR framework/driver-error 0ms loader constraint violation: when resolving method
'void top.wcpe.mc.mpmt.domain.capability.PlatformCapabilityExample.<init>(
   ... top.wcpe.mc.mpmt.kernel.port.PersistencePort ...)'
the class loader 'mpmt-bukkit-acceptance-...jar' @6ea837c and the class loader
'mpmt-bukkit-...jar' @66e7e358 have different Class objects for the type
top/wcpe/mc/mpmt/kernel/port/PersistencePort
TOTAL 1 PASS 0 FAIL 0 ERROR 1 SKIP 0
RESULT FAIL
```

根因是同一个类被两个插件各自的类加载器重复加载。实测确认：

- 两个 jar **各含一份** `top/wcpe/mc/mpmt/kernel/port/PersistencePort.class`；
- 验收 jar 共 203 个条目，其中 **104 个是产品侧类**（`top/wcpe/mc/mpmt/{kernel,core,protocol}`），
  超过一半；
- 来源是打包约定里验收 jar 的 shade 用了
  `configurations = [acceptanceRuntimeClasspath]`，而该配置的依赖树直接包含
  `project(':core:protocol')` → `:core:kernel` → `:core:runtime` / `:core:server` / `:core:client`，
  shade 把项目依赖整体打入；
- 打包约定中 `BUKKIT_ACCEPTANCE_JAR_PRODUCT_INCLUDES` 的本意只是并入 L4 适配器
  （`top/wcpe/mc/mpmt/platform/bukkit/version/**` 与对应 `META-INF/services` 声明），
  并未打算并入 kernel / core / protocol。

另一个加剧因素：验收插件的 `plugin.yml` 声明的是 `softdepend: [MultiPlatformModTemplate]`
而非 `depend`。软依赖不保证产品插件先加载，也不保证产品类对验收插件可见——即使去掉重复类，
运行期能否解析到产品类也缺保障。

## 决策

验收插件不再 shade 产品侧项目依赖，产品类在运行期由产品插件提供；同时把 4 条 Bukkit 车道
验收 `plugin.yml` 的 `softdepend` 改为 `depend`，由 Bukkit 的插件依赖机制保证加载顺序与类可见性。

## 理由

- **同名类只应存在一份**。两套 ClassLoader 各持一份同名类时，任何跨插件的方法签名匹配都会
  失败，这类问题表现为「方法明明存在却报 loader constraint violation」，排查成本极高；
- **验收插件本就不该是产品类的第二个来源**。打包约定里 `BUKKIT_ACCEPTANCE_JAR_PRODUCT_INCLUDES`
  已经写明只并入 L4 适配器，当前把 kernel/core/protocol 打进去是实现与设计意图的偏离；
- **`depend` 是 Bukkit 提供的既有机制**。验收对象就是产品插件，硬依赖语义正确；软依赖只在
  「产品插件可能不存在」时才合理，而验收场景下产品必然存在；
- 保留验收 jar 自带产品类的替代做法会把「两份同名类」长期固化，后续任何跨插件调用都可能再踩。

## 后果

- 验收 jar 体积明显下降（当前 203 条目中 104 条是产品类），且内容与「验收驱动」职责一致；
- **发布 jar 的字节内容会变**：`mpmt-bukkit-acceptance-<mc>-<version>.jar` 是登记过的发布产物，
  按护栏要求须更新 ADR 基线并重跑验收生成新报告；产品 jar（`mpmt-bukkit-<mc>-<version>.jar`）
  不受本决策影响；
- 受影响范围：`build-logic/build-conventions` 的 Bukkit 打包约定，以及
  `platform/bukkit/{1.12.2,1.20.1,1.21.1,26.2}/src/acceptance/resources/plugin.yml` 四处；
- 验收插件运行期必须与产品插件同服部署——这本来就是验收链路的前提，故不构成新约束。

## 备选方案

- **保留验收 jar 自带产品类，改用 ClassLoader 隔离策略**：不必动 `plugin.yml` 与 ADR，但要让两套
  同名类长期共存并依赖隔离细节，后续任何跨插件调用都可能再次触发同类问题；隔离策略本身也需要
  额外设计与验证，净收益低于直接消除重复来源。
- **把产品类 relocate 到验收插件的私有包名**：可避免冲突，但验收代码要引用产品类型（如
  `PlatformCapabilityExample`、`PersistencePort` 出现在构造器签名里），relocate 后类型不再与产品
  插件一致，验收测到的就不是真实产品的类——违背验收目的。
