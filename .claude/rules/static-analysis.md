# 代码风格与静态检查（防风格 / 质量漂移）

> 统一格式化与静态检查工具——风格一致、低级问题挡在合并前。若启用 CI，将其设为合并门禁。

## 1. 本项目工具链

本仓库主体是 Java（L0–L4 业务代码）+ Gradle Kotlin DSL（构建脚本）。固定以下工具并写进工程配置、版本锁定：

- **Java**：`spotless`（google-java-format 或 palantir-java-format）做格式化 + `checkstyle` 或 `pmd` 做静态检查。Lombok 模块需配套 `lombok.config`。
- **L0–L2 Java 8 API 强制**：用 `javac --release 8`（JDK 11+ 经 ct.sym 限制只引用 JDK 8 API）或 `animal-sniffer`（java18 baseline）——仅锁 `sourceCompatibility` 不拦 Java 9+ API 误用（`List.of`/`Optional.isEmpty` 等），会在 1.12.2 运行期 `NoSuchMethodError`（见 ADR-0004）。
- **Gradle Kotlin DSL**：`ktlint`（对 `*.gradle.kts` / buildSrc）做格式化与基本检查。
- 工具与规则版本固定（写进 `build.gradle.kts` / 配置文件），避免不同机器结果不一致。

> 待第二期引入平台胶水（可能含少量 Kotlin / Mixin）时，再按需补充对应工具，仍遵循"格式化 + lint"组合。

## 2. 要求

- **本地（当前底线）**：提交前自行跑 `spotlessCheck` + 静态检查，不把格式问题留给后续。这是当前仓库的强制底线。
- **CI 门禁（可选 / 计划）**：当前仓库尚未配置 CI。若启用 CI，把 lint + 格式检查 + 测试设为合并门禁（与 `testing-and-quality.md` 同级），未过不允许合并。
- **依赖漏洞**：用 JVM 生态的漏洞发现工具（如 `org.owasp:dependency-check` Gradle 插件或 `osv-scanner`）扫描依赖；启用 CI 后纳入门禁。
- 工具与规则版本固定（写进配置 / 构建），避免不同机器结果不一致。

## 3. 与现有规则的关系

- 本规则是 `testing-and-quality.md` 的补充：测试管"行为对不对"，静态检查管"写法干不干净"。
- 禁用某条检查要在**配置里集中声明并注明原因**（如 checkstyle suppressions 文件 / `@SuppressWarnings` 配合说明），不在代码里零散关闭（除非有明确理由并写明）。
