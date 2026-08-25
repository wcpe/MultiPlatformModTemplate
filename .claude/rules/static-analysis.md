# 代码风格与静态检查（防风格 / 质量漂移）

> 统一格式化与静态检查工具——风格一致、低级问题挡在合并前。GitHub Actions 已将其作为远端构建门。

## 1. 本项目工具链

本仓库主体是 Java（L0–L4 业务代码）+ Gradle Kotlin DSL（构建脚本）。**以下工具已接入并版本锁定**（根构建经 `subprojects` 统一配置，4 个独立 includeBuild 平台各自镜像同一套，共享仓库根 `config/` 规则集；**严格门禁**——违规即失败构建）：

- **样式审查（Java）**：`checkstyle` 10.17.0，裁剪规则集 `config/checkstyle/checkstyle.xml`（聚焦导入卫生 / 命名 / 结构，不强制具体格式化；容纳中文测试方法名、L4 `vX_Y` 架构命名、Mixin `$` 注入命名）。
- **代码异味 / 源码规则（Java）**：`pmd` 7.0.0，裁剪规则集 `config/pmd/ruleset.xml`（未用 / 空块 / 吞异常 / 多线程缺陷等高信号项；不取 `UnusedFormalParameter`——与回调密集设计冲突）。
- **缺陷检测（字节码）+ 安全审查（Java）**：`spotbugs` 6.0.26（effort MAX、置信度 MEDIUM）+ `findsecbugs` 1.13.0 挂其上；排除过滤器 `config/spotbugs/exclude.xml`（集中登记 Lombok 生成代码 / 框架契约 / 工具误报，逐条注明）。**仅生产码（spotbugsMain）严格门禁**，test / acceptance / gametest 等非 main 源集宽松。
- **测试覆盖率（Java）**：`jacoco`——核心模块（L0–L2 + 共享 + bukkit）设 LINE 覆盖率底线 0.70 门禁并入 `check`；平台 includeBuild 胶水（fabric/forge/neoforge/sponge，单元测试少、靠 realserver）仅出报告、不设底线。
- **分离度 / 分层回归断言**：`ArchUnit` 1.3.0（core-domain 测试）——校验 L0 零平台依赖、功能域互不依赖且无环（ADR-0001/0011）。
- **Lombok**：仓库根 `lombok.config`（`addLombokGeneratedAnnotation`）使生成代码带 `@lombok.Generated`、避免静态分析误报；并登记为 `JavaCompile` 输入。
- **L0–L2 Java 8 API 强制**：用 **JDK 8 工具链**编译（API 面即 JDK 8，强于 `--release 8`）——见 ADR-0004。
- **Kotlin / Gradle Kotlin DSL**：`ktlint` 12.1.1（检 `*.gradle.kts`，规则经 `.editorconfig` 裁剪）；`detekt` 1.23.7 + `kover` 0.8.3 **已前瞻接入**（当前无 Kotlin 源、近空扫 / no-op，第二期引入 Kotlin 源即生效）。

**暂不接入（带原因，需要时再加）**：
- **Error Prone**：其编译器插件需 JDK 11+ javac，与 L0–L2 刻意的 **JDK 8 工具链**（ADR-0004）直接冲突；编译期缺陷检测由 SpotBugs（字节码）+ PMD（源码）覆盖。
- **OWASP Dependency-Check**：需 `NVD_API_KEY` 方可用（否则限流 / 失败）；启用 CI 配 key 后再加（本项目第三方运行期依赖极少、仅 snakeyaml/gson 且已 relocate）。
- **Semgrep**：独立 CLI、非 Gradle 插件；安全审查现由 FindSecBugs 在字节码层覆盖 Java，待第二期 Kotlin 源或 CI 步骤再接。
- **SonarQube**：需运行的 Sonar 服务器 / 令牌；本地工具已足够。

> 工具与规则版本固定（写进 `build.gradle.kts` / `config/`），避免不同机器结果不一致。

## 2. 要求

- **本地（当前底线，强制）**：提交前自行跑静态检查（`./gradlew check` 含 Checkstyle/PMD/SpotBugs/JaCoCo/ktlint/detekt/ArchUnit；各 includeBuild 平台 `./gradlew -p platform-X check`），违规即失败、不把问题留给后续。注：全工具一次 `check` 在低内存机器可能 OOM，可分工具 / 分模块跑。
- **CI 门禁（强制）**：`.github/workflows/ci.yml` 在 pull request、`main`/`dev` 推送与手动触发时，以固定 mc-testkit Maven Local 回退、独立 Java/Gradle 车道和 `:buildAll` 执行上述检查与测试；远端未绿不得合并。CI 不运行或替代 realserver/R7 真服门。OWASP Dependency-Check 仍因需要 `NVD_API_KEY` 未接入。
- 工具与规则版本固定（写进配置 / 构建），避免不同机器结果不一致。

## 3. 与现有规则的关系

- 本规则是 `testing-and-quality.md` 的补充：测试管"行为对不对"，静态检查管"写法干不干净"。
- 禁用某条检查要在**配置里集中声明并注明原因**（如 checkstyle suppressions 文件 / `@SuppressWarnings` 配合说明），不在代码里零散关闭（除非有明确理由并写明）。
