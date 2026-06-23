# Git 提交规范

> 适用于本仓库所有 `git commit` 操作。

## 1. 提交信息语言（强制）

- **标题（Description）与正文（Body）必须使用简体中文。** 禁止英文、日文等非中文。
- Conventional Commits 的 type 与 scope 仍用英文小写（`feat`/`fix`/`refactor`/`docs`/`chore`/`test`/`build`/`ci`/`perf`/`style`）。
- **禁止在提交信息中添加任何 AI 签名或尾注**，例如 `Generated with ...`、`Co-Authored-By: ...`。不要附加作者/工具/来源署名。

### 1.1 标题格式

```
<type>(<scope>): <中文描述>
```

- `<scope>`：英文小写模块/能力域，可选。按本仓库分层与平台划分填写，例如：
  - 分层域：`domain`（L0 功能域）、`runtime`（框架编排）、`server`（服务端公共逻辑）、`client`（客户端公共逻辑）、`protocol`（跨端协议）、`spi`（平台抽象）。
  - 平台域：`bukkit`、`paper`、`folia`、`sponge`、`fabric`、`forge`、`neoforge`。
  - 版本适配域：`v1_12`、`v1_20`、`v1_21`、`v26_2`（按锚点版本；26.2 为 MC 新版号方案，无 `1.` 前缀）。
  - 工程域：`build`、`ci`、`docs`、`config`。
- `<中文描述>`：简洁陈述本次做了什么，必须中文，结尾不加句号。

### 1.2 正文格式

- 用空行与标题分隔，中文撰写，可用 `-` 列要点。
- 说明"为什么改"与"改动要点"，不逐行复述 diff。

### 1.3 示例

✅ 正确
```
feat(spi): 新增平台能力探测 FeatureGate 接口

- 抽象出"是否 Folia 区域调度 / 该版本是否有某 API"的能力查询
- 各平台胶水按需实现，杜绝在公共逻辑里散落平台 if-else 判断
```

❌ 错误（标题英文）
```
feat(spi): add platform feature gate
```

### 1.4 禁止阶段性词语（强制）

提交按**功能点**描述，不按**开发阶段**描述。commit message（标题与正文）**禁止**出现阶段 / 批次性词语：`Phase 0`、`P0` / `P1` / `P2`、`MVP`、`Sprint`、`第一期` / `本次迭代` 等。它们说的是"项目走到哪一步"而非"这次改了什么"，会随时间失效、也无法追溯到具体改动。

✅ 正确（描述功能点）
```
feat(fabric): 接入 Fabric 客户端入口并装配 SPI
```

❌ 错误（用阶段词代替功能描述）
```
feat: 完成 MVP 第一期核心功能
chore: P1 Sprint 3 的若干功能
```

## 2. 文档入库边界（强制）

判据：**活文档（长期维护、是真源）入库；易朽稿（做完即弃）留 `.tmp/`。**

### 2.1 应当入库的耐久文档

- 产品 / 需求：`README.md`、`CHANGELOG.md`、`docs/PRD.md`（活文档，随需求变更同 PR 更新）。
- 架构：`docs/ARCHITECTURE.md`、`docs/adr/*.md`、`docs/API.md`。
- 协作治理：`docs/CONTRIBUTING.md`、`.claude/rules/*.md`。

### 2.2 严禁入库的易朽过程稿（已由 `.gitignore` 排除 `/.tmp/`）

- 实施计划 / 里程碑 / 路线图：`实施计划.md`、`PLAN.md`、`roadmap.md` 等。
- 过程性报告：`IMPLEMENTATION.md`、`执行报告.md`、`分析.md`、`audit-*.md` 等。
- AI 助手过程性笔记、交流稿、思路记录。

> 例：PRD 是活的需求规格 → 入库 `docs/`；实施计划易朽 → 留 `.tmp/`。文档与代码的同步要求见 `docs/CONTRIBUTING.md` 与 `.claude/rules/doc-sync.md`。

## 3. 最小提交粒度（强制）

- **验证门通过才提交**：`git commit` 前，本次变更必须已过验证门（判据见 `testing-and-quality.md` §1）。**门未全绿、或完成被实测推翻时，不得提交**；涉及实机 / 集成维度的，待用户确认验收通过再提交，先继续修到门过再提交。
- **独立可编译**：每个 commit 落地后代码都能编译 / 构建通过，不留"半截"提交。
- **只做一件事**：一个 commit 只对应一个功能点 / 一个修复 / 一次重构，无关改动不混入。
- **不混类型**：不在同一 commit 里混 `feat` / `fix` / `refactor`——各自独立提交（顺手发现的 bug 单独 `fix`，重构单独 `refactor`）。

✅ 正确（拆成独立、各自可编译、各做一件事）
```
feat(domain): 冒烟特性新增跨端计数领域逻辑
fix(protocol): 修复握手包版本协商的边界判断
refactor(runtime): 提取端口装配为独立装配器
```

❌ 错误（一个 commit 混了功能 + 修复 + 重构）
```
feat: 加计数逻辑，顺便修个协议 bug 并重构装配
```

❌ 错误（半截、单独不可编译）
```
feat(fabric): 加客户端入口（SPI 还没接，编译不过）
```

## 4. 其他约束

- 禁止跳过 hooks（`--no-verify`）。禁止对已 push 的提交 `--amend`。
- 提交前确认未包含 `.env` / 凭据 / 大型二进制。
