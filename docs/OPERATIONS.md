# 运维手册：MultiPlatformModTemplate

> 构建、部署、调试、升级、回滚的操作指南。运维方式变化时更新。
> 当前为骨架阶段：构建脚本尚未落地，下列命令为**约定形态**，随构建模块（见 ADR-0007）建立后在此填实具体任务名。

## 1. 构建

- 构建工具：Gradle **复合构建**（Kotlin DSL）。核心 L0–L2（纯 `java-library`·Java 8）与 Bukkit 家族（`platform-bukkit`·普通 Java+shadow）为根构建常规模块；Fabric/Forge/NeoForge/Sponge 各为经 `includeBuild` 引入的独立构建，隔离各自工具链（Loom / ForgeGradle / NeoGradle / SpongeGradle），核心经依赖替换共享（见 ADR-0007）。第三方依赖 relocate 到 `top.wcpe.mc.mpmt.libs.*`、core 打进各产物的方式见 ADR-0012；M0 先做打包 spike，必要时回退"core 发 mavenLocal + 各 loader shadow 消费"。
- 预期产物（每平台一件，内含核心 + 对应 L3/L4 胶水）：
  - Bukkit 家族：插件 jar（含 `plugin.yml`）。
  - Fabric：Loom 重映射 mod jar（`fabric.mod.json`）。
  - Forge / NeoForge：mod jar（`mods.toml` / `neoforge.mods.toml`）。
- 版本号注入：根 `VERSION` 是唯一来源，构建注入各产物。
- 全量构建 / 单模块构建 / 跑测试的具体 Gradle 任务名，待构建模块落地后补全于此。

## 2. 部署

- **服务端**：把对应平台插件 / mod 放入服务端的 `plugins/`（Bukkit 家族）或 `mods/`（Fabric/Forge/NeoForge 服务端）目录，重启。
- **客户端**：把对应 loader 的 mod 放入 `.minecraft/mods/`（需先装对应 loader 与依赖，如 Fabric API / Kotlin loader 视实现而定）。
- **组合**：任意服务端 + 任意客户端可组合（如 Paper 服务端 + Fabric 客户端），双方经协议通信，握手做版本协商。
- 健康检查：启动日志应出现"平台发现成功 + 唯一活跃平台 + 端口装配完成"；冒烟特性按预期运行。

## 3. 开发期调试

- 各平台提供开发期运行入口（如 Fabric Loom 的 `runClient`/`runServer`、Paper 的 run-paper、Forge 的 `runClient`/`runServer`），具体任务名待构建落地补全。
- 开发服 / 客户端运行目录（`run/` 等）已在 `.gitignore` 排除。

## 4. 升级

- 升级部署的产物：替换各平台产物 jar 为新版本；若涉及协议破坏性变更，**服务端与客户端须同步升级到兼容区间**（见 CHANGELOG 迁移说明与协议 `MIN_SUPPORTED`）。
- 新增平台 / 版本支持是增量的：旧产物不受影响。

## 5. 回滚

- 出问题回退到上一个已知良好版本：替换回旧产物 jar。
- 协议层回滚需注意：若新版本提升了 `MIN_SUPPORTED`，回滚端与未回滚端可能不兼容——按 CHANGELOG 迁移说明成对回滚。
- 代码层回滚优先 `git revert`（见 `sdd-rollback-change`）。

## 6. 排障

- **启动即失败 / 报"无平台 / 我方多入口同时激活"**：检查产物是否对应正确平台、是否在同进程误装了多个我方入口（融合服上 Bukkit/Forge 并存正常，应只激活 Bukkit 入口，见 ADR-0008）、`META-INF/services` 是否就位（SPI 发现，见 ADR-0002）。
- **跨端不通 / 握手失败**：检查两端协议版本是否在兼容区间（`CURRENT` / `MIN_SUPPORTED`）。
- **某版本行为异常**：检查 MC 版本探测与 `vX_Y` 装配是否匹配（L4，见 ADR-0003）。
- 关键日志为中文分级日志（ERROR/WARN/INFO/DEBUG），定位时按级别筛查。
