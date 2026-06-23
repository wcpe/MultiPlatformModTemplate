# ADR-0005：构建用自定义多模块 Gradle，不用 Architectury

## 状态
已被 [ADR-0007](0007-composite-build-loader-isolation.md) 取代（其"自定义 Gradle、不用 Architectury"的核心决策仍有效，构建组成细节由 ADR-0007 修订为复合构建）。

## 背景
项目需同时产出 Bukkit 家族插件（Paper/Folia/Sponge）与模组加载器制品（Fabric/Forge/NeoForge）。常见的"多加载器"方案 Architectury（基于 Loom）只覆盖 Fabric/Forge/NeoForge 这类 mod 加载器，**不覆盖 Bukkit/Spigot/Paper/Sponge 服务端软件**——而桥接服务端软件正是本项目的核心诉求之一。

## 决策
采用**自定义 Gradle 多模块**（Kotlin DSL）：
- L0–L2 为纯 `java-library`（Java 8），被所有平台模块依赖。
- 各平台模块用各自原生工具链：Bukkit 家族用普通 Java + shadow 打包插件 jar；Fabric 用 Fabric Loom；Forge 用 ForgeGradle；NeoForge 用 NeoGradle。
- 版本号唯一来源是根 `VERSION` 文件，构建注入各平台产物。

## 理由
- 唯一能在一个仓库内同时覆盖"服务端软件 + mod 加载器"两个世界的方案。
- 纯核心模块与平台工具链解耦：核心一次编译，各平台各自打包。
- 不被单一框架（Architectury）的边界绑死，扩展平台自由。

## 后果
- 正面：覆盖全平台；各平台工具链独立演进、互不掣肘。
- 负面：构建配置比单框架方案复杂，需要维护多套打包逻辑与 JDK toolchain（见 ADR-0004）。
- 约束：本文件属"关键文件保护"范畴，构建脚本变更需经评审；新增平台时在此模式下追加模块，不引入与之冲突的统包框架。

## 备选方案
- **Architectury**：不覆盖 Bukkit/Sponge——否决（不满足核心诉求）。
- **每平台独立仓库**：核心难以共享、版本难对齐——否决。
- **Maven 多模块**：JVM 多加载器生态工具（Loom/ForgeGradle/NeoGradle）几乎都以 Gradle 为一等公民，Maven 支持弱——否决。
