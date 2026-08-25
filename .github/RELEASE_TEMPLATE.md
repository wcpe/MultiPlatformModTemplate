# 发布说明模板

> 创建 GitHub Release 时复制本模板填写。对应规格 FR-17 / [`docs/VERSIONING.md`](../docs/VERSIONING.md)。

## 本版本亮点

- <1–3 条主要变更，写"为什么用户该关心"，勿逐文件复述>

## 产物

由 `./gradlew :collectReleaseArtifacts` 生成，上传 `build/dist/` 下对应 jar。

| 平台 | 典型文件名 |
|---|---|
| Bukkit/Paper/Folia 1.20.1 | `mpmt-bukkit-1.20.1-X.Y.Z.jar` |
| Bukkit/Paper 1.21.1 | `mpmt-bukkit-1.21.1-X.Y.Z.jar` |
| Bukkit/CatServer 1.12.2 | `mpmt-bukkit-1.12.2-X.Y.Z.jar` |
| Bukkit/Paper 26.2 | `mpmt-bukkit-26.2-X.Y.Z.jar` |
| Fabric 1.20.1 | `mpmt-fabric-1.20.1-X.Y.Z.jar` |
| Fabric 1.21.1 | `mpmt-fabric-1.21.1-X.Y.Z.jar` |
| Fabric 26.2 | `mpmt-fabric-26.2-X.Y.Z.jar` |
| Forge 1.20.1 | `mpmt-forge-1.20.1-X.Y.Z.jar` |
| Forge 1.21.1 | `mpmt-forge-1.21.1-X.Y.Z.jar` |
| Forge 1.12.2（客户端伴侣） | `mpmt-forge-1.12.2-X.Y.Z.jar` |
| Forge 26.2 | `mpmt-forge-26.2-X.Y.Z.jar` |
| NeoForge 1.20.2 | `mpmt-neoforge-1.20.2-X.Y.Z.jar` |
| Sponge 1.20.1 | `mpmt-sponge-1.20.1-X.Y.Z.jar` |

## 测试门禁

- [ ] 核心单测：`:core:domain:test` / `:core:protocol:test` / `:modules:acceptance:test` 全绿
- [ ] 版本矩阵门：`:runVersionMatrixGate` BUILD SUCCESSFUL（P2 核心车道）
- [ ] P3 R7 门：`:runP3R7Gate` 使用本轮 `RUN_ID`、开始毫秒和实际 Forge 服务端 JAR，通过严格当前报告校验
- [ ] realserver 合规报告 + 用户实机确认：见 `CHANGELOG.md` 本版本段

## 升级注意

- <破坏性变更 / 迁移步骤；无则写「无」>
- 协议通道名 / `VERSION` / 最低 `MIN_SUPPORTED` 是否变化

## 相关链接

- CHANGELOG：`CHANGELOG.md` 本版本段
- PRD 交付 FR：`docs/PRD.md` §4
- 上手指南：`docs/HOWTO-CLONE-AND-WRITE-PLAY.md`
