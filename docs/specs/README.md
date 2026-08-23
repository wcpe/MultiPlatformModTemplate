# 功能规格（specs）

非平凡功能在动手前先写一份**工作规格**：一个功能一个文件 `docs/specs/<feature>.md`，把"要什么 / 怎么做 / 任务 / 验收"集中一处，再实现。模板见 [`_template.md`](_template.md)。

## 当前规格

- [P2 版本矩阵与工具链隔离](p2-version-matrix.md)（已交付@v0.2.0，FR-12/FR-25）
- [第三期 · 平台规模化与对外上手](p3-platform-scaling-and-onboarding.md)（开发中，FR-16/FR-17/FR-18；基线 `v0.2.0`）
- [FR-16 · MC 26.2 版本适配（冻结）](fr-26_2-adapter.md)（T1–T4 的本地验证已完成：仅 Paper/Fabric/Forge，Folia 无 26.2；三车道同轮 R7 与根门已通过，待用户实机确认）

## 何时写

- **写**：新增一个非平凡功能 / 能力（尤其 P2/P3），或任何够得上一个分支 / PR 的功能。
- **不写**：小改动、bug 修复、重构、依赖升级——走 PRD 状态列 + 对应技能即可。别为每个小改动建 spec（简单优先）。

## 与项目级文档的分工（别双源打架）

- `docs/PRD.md`：持久路线图——该功能在 PRD 是**一行 FR + 状态**。
- `docs/specs/<feature>.md`：该功能**开发期的详细工作规格**（比 PRD 那行细）。
- 交付后：持久真相归并回 PRD（FR 标 `已交付@vX.Y.Z`）+ `ARCHITECTURE.md`（更新到现状）+ ADR（若有架构决策）；spec 留作该功能的历史记录，基本不再改。

## 怎么用

1. 复制 `_template.md` 到 `docs/specs/<feature>.md`。
2. 填需求 / 设计 / 任务 / 验收。
3. 按 `sdd-develop-feature` 技能实现，对着 spec 的任务与验收推进。
4. 交付后归并回项目级文档（见上）。

> spec 是 🌡 中频文档：功能开发期动，交付后基本不动。涉及架构决策时在 spec 里**引用** ADR，不重复决策正文。
