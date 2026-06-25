# 功能规格：平台无关配置加载模块 core-config

> 状态：开发中　·　关联 PRD：FR-29　·　分支：main（单 FR，随迭代直接落 main）

## 1. 背景与目标
配置加载本就平台无关、客户端与服务端应共享，否则各平台各写一套、易漂移易错（[ADR-0010](../adr/0010-config-and-resource-paths.md)）。本模块提供 L1 共享配置加载工具，把 YAML / JSON 配置文件加载为类型化模型。属第一期（P1）基础设施交付物，与 `core-paths`（预设目录）并列、解耦于命令框架（ADR-0009）。

## 2. 需求（要什么）
- 把 YAML（`.yml`/`.yaml`）与 JSON（`.json`）配置文件加载为调用方给定的类型化模型（POJO）。
- 平台无关、纯 Java 8，客户端 / 服务端共用同一份加载逻辑。
- 按文件扩展名自动判别格式；也支持显式指定格式。
- 加载失败（文件缺失 / IO 错误 / 解析错误 / 未知格式）抛清晰的业务异常，不吞异常。
- 范围内：YAML + JSON 两种格式的「文件 → 类型化模型」只读加载；UTF-8。
- 不做（范围外）：配置写回 / 序列化导出；配置热重载 / 监听；配置校验 schema；kebab-case→camelCase 字段名映射（按所给类型字段名精确映射，留待真实需要时再加）；基目录解析（由 `core-paths` + `DataDirectoryPort` 负责，调用方组合，不在本模块耦合）。

## 3. 设计（怎么做）
新增 L1 模块 `core-config`（`java-library`、JDK 8 工具链），包 `top.wcpe.mc.mpmt.core.config`：

- `ConfigFormat`（枚举）：`YAML`（扩展名 `yml`/`yaml`）、`JSON`（扩展名 `json`）；`fromFileName(String)` 按扩展名（忽略大小写）判别，未知即抛 `IllegalArgumentException`——以枚举多态消灭格式 if-else（反模式禁令 §6）。
- `ConfigLoader`（接口）：`<T> T load(Reader, Class<T>)`，格式无关的加载契约。
- `YamlConfigLoader` / `JsonConfigLoader`：分别用 snakeyaml（`loadAs`，仅目标类型、不放行任意全局标签）、gson（`fromJson`）实现，策略模式各司其一。
- `ConfigService`（门面）：持 `EnumMap<ConfigFormat, ConfigLoader>`；`load(Path, Class<T>)` 按文件名判格式、以 UTF-8 try-with-resources 开 reader、委派对应 loader；`load(Path, ConfigFormat, Class<T>)` 显式指定格式。IO / 解析失败统一包成 `ConfigLoadException`。
- `ConfigLoadException`（运行期业务异常）：携带文件 / 原因，避免裸抛底层异常或吞异常。

依赖：`org.yaml:snakeyaml`（与各平台一致 2.2）+ `com.google.code.gson:gson`（Java 8 兼容）。源码用其原始包名；relocate 到 `top.wcpe.mc.mpmt.libs.*` 是各平台 shade 期职责（ADR-0012），本模块不处理。本模块零项目依赖（不依赖 core-domain/core-paths），保持单一职责与可复用。

## 4. 任务拆分
- [ ] settings 注册 `core-config` + 模块 `build.gradle.kts`（JDK 8、snakeyaml+gson、junit）
- [ ] 测试先行：`ConfigFormat` / `YamlConfigLoader` / `JsonConfigLoader` / `ConfigService` 红→绿
- [ ] 实现上述类
- [ ] 文档同步：PRD §4 FR-29 状态、ARCHITECTURE「当前落地」、CHANGELOG 未发布段

## 5. 验收标准
- `ConfigFormat.fromFileName` 对 `yml`/`yaml`/`json`（含大写）正确判别，无扩展名 / 未知扩展名抛异常。
- YAML / JSON 各自把合法内容加载为 POJO（嵌套 + 列表 + 基本类型）；非法 / 截断内容抛 `ConfigLoadException` 而非裸异常或静默。
- `ConfigService.load(Path, Class)` 按扩展名走对应 loader；文件缺失抛 `ConfigLoadException`；显式 `load(Path, ConfigFormat, Class)` 覆盖扩展名判别。
- 纯 JVM 单元测试全绿，`./gradlew :core-config:test` 通过——本模块无实机维度，单元测试即完整验收。

## 6. 风险 / 待定
- snakeyaml 2.x 限制任意全局标签实例化；用 `loadAs(目标类型)` 在安全范围内加载，避免反序列化任意类型。
- 字段名按精确名映射（不做 kebab→camel），与 `config-files` 规则的 kebab-case 约定在「项目自有配置文件」上需调用方自定义字段名或后续补映射——本期不做，避免镀金。
