# 版本节奏

> 对应规格：[`specs/p3-platform-scaling-and-onboarding.md`](specs/p3-platform-scaling-and-onboarding.md) §4.2 / FR-17。  
> 发版技能：`sdd-release-version`（本地 release 提交 + 附注 tag，**永不自动 push**）。

## 1. 版本来源

根目录 **`VERSION`** 文件是**唯一**版本来源。各平台 `build.gradle(.kts)` 读该文件注入产物坐标（如 `mpmt-bukkit-1.20.1-0.2.0.jar`）。  
禁止在模块内硬编码版本号。

## 2. 语义化版本（SemVer）

与 Conventional Commits 对齐，取 `vX.Y.Z` 上一 tag 到 HEAD 的最高级：

| 变更类型 | 等级 |
|---|---|
| 对外接口 / 配置 / 协议 / 数据模型**不兼容**或含 `BREAKING` | **主版本 +1**（`X`） |
| `feat` 或新增用户可见能力 | **次版本 +1**（`Y`） |
| `fix` / `perf` / `refactor` / `docs` / `chore` 且无新增能力 | **修订版本 +1**（`Z`） |

当前正式版见 `VERSION` 与 `CHANGELOG.md` 最近一个 `## [X.Y.Z]` 段。

## 3. 发版流程（本地）

由 `sdd-release-version` 驱动，步骤摘要：

1. **阻断验证**：核心单测 + 当期约定门 + `:collectReleaseArtifacts` 全绿。P2 release 使用 `:runVersionMatrixGate`（只覆盖 R1–R6，**不含 26.2**）；P3 26.2 release 还必须过 `:runP3R7Gate` 的当前三车道 R7 证据。
2. **CHANGELOG 先行**：把 `## 未发布版本` 整理为 `## [X.Y.Z] - YYYY-MM-DD`，清空未发布段。
3. **升号**：改 `VERSION` 为 `X.Y.Z`。
4. **文档同步**：交付的 FR 在 `docs/PRD.md` 标 `已交付@vX.Y.Z`；必要时同步 OPERATIONS / 规格状态 / README。
5. **独立 release 提交**：`chore(release): 发布 X.Y.Z`（只含版本与必要文档，不混功能改动）。
6. **本地附注 tag**：`git tag -a vX.Y.Z -m "发布 vX.Y.Z"`。
7. **不 push**；是否推远程由维护者决定。本仓库已启用为公开 GitHub Template；GitHub Release 仍是外部状态变更，须先获维护者明确授权。本地 tag 或本文件不能替代 GitHub Release，当前尚无 `v0.3.0` 或 GitHub Release。

## 4. 产物聚合

```bash
./gradlew --no-daemon :collectReleaseArtifacts
# 输出：build/dist/{bukkit,fabric,forge,neoforge,sponge}/
```

Forge 1.12.2 / 1.21.1 / 26.2 须用各自目录自有 wrapper 先构建，产物才会被捞入 dist（见 [`OPERATIONS.md`](OPERATIONS.md)）。26.2 聚合前还须完成 Paper/Fabric 的 Java 25 构建；聚合到 `dist` 不等于 R7 真服验收通过。

## 5. 路线图

| 期 | 主题 | 状态 |
|---|---|---|
| 第一期（MVP） | 分层骨架 + 全平台基础网络 + 异构桥接 | 已交付 @v0.1.0 |
| 第二期 | 1.21.1 / 1.12.2 多版本矩阵（FR-12） | 已交付 @v0.2.0 |
| 第三期 | 26.2 + 模板发布 + 上手文档（FR-16/17/18） | 开发中：三车道同轮 R7 与根门已通过，公开 GitHub Template 已启用；仍待用户实机确认、干净克隆复现与 `v0.3.0` 对外 Release |

详见 [`PRD.md`](PRD.md) §4 / §7。
