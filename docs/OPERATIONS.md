# 运维手册：MultiPlatformModTemplate

> 物理布局：`core/` · `platform/<loader>/{api,版本}/` · `modules/`。  
> Gradle 工程名保持稳定；`-p` 与 `projectDir` 指向收纳后的路径。

## 1. 构建

```bash
# 根 L0–L2 + 平台 api
./gradlew --no-daemon :core:domain:build :core:spi:build
./gradlew --no-daemon :platform:bukkit:bukkit-api:build :platform:fabric:fabric-api:build

# Bukkit 版本产物
./gradlew --no-daemon :platform:bukkit:1.20.1:shadowJar
./gradlew --no-daemon :platform:bukkit:1.12.2:shadowJar
./gradlew --no-daemon :platform:bukkit:1.21.1:shadowJar

# Fabric / Forge / Neo / Sponge（-p 用物理路径）
./gradlew -p platform/fabric/1.20.1 --no-daemon remapJar
./gradlew -p platform/fabric/1.21.1 --no-daemon remapJar
./gradlew -p platform/forge/1.20.1 --no-daemon reobfShadowJar
./gradlew -p platform/neoforge/1.20.2 --no-daemon shadowJar
./gradlew -p platform/sponge/1.20.1 --no-daemon shadowJar

# 聚合可发布 jar → build/dist/{bukkit,fabric,forge,neoforge,sponge}/
./gradlew --no-daemon :collectReleaseArtifacts
# 全量构建 + 聚合
./gradlew --no-daemon :buildAll
```

Forge 跨代（自有 launcher，目录在 `platform/forge/`，禁止从根嵌套 gradlew）：

| MC | 目录 | JDK / Gradle |
|---|---|---|
| 1.21.1 | `platform/forge/1.21.1/` | Java 21 + 8.12.1 |
| 1.12.2 | `platform/forge/1.12.2/` | Java 8 + 5.6.4（**client-only**） |

## 2. 平台 API 模块

| 工程名 | 物理路径 | 用途 |
|---|---|---|
| `:platform:bukkit:bukkit-api` | `platform/bukkit/bukkit-api` | Bukkit 家族对外契约 |
| `:platform:fabric:fabric-api` | `platform/fabric/fabric-api` | Fabric 对外契约 |
| `:platform:forge:forge-api` | `platform/forge/forge-api` | Forge 对外契约 |
| `:platform:neoforge:neoforge-api` | `platform/neoforge/neoforge-api` | NeoForge 对外契约 |
| `:platform:sponge:sponge-api` | `platform/sponge/sponge-api` | Sponge 对外契约 |

玩法扩展 / 跨版本 common 应依赖 **api**，不要依赖版本实现 jar。

## 3. 版本矩阵 / 真服

```bash
./gradlew :listRealServerLanes
./gradlew :verifyVersionMatrixBuild
./gradlew :runVersionMatrixGate
```

请使用 **绝对路径** `:task`，避免无 `:` 时匹配到子工程同名 `runRealServerAcceptance`。

Paper + Fabric 客户端：

```bash
./gradlew :platform:bukkit:1.20.1:ensurePaperRealServerHost \
  -Pmpmt.realserver.autoHost=true -Pmpmt.realserver.waitForReport=true
./gradlew -p platform/fabric/1.20.1 runAcceptanceClient \
  -Pmpmt.acceptance.server=127.0.0.1:25599
```

Forge 1.21.1 专用服（独立 launcher）：

```bash
cd platform/forge/1.21.1
./gradlew --no-daemon printRealServerAcceptanceRecipe
# 起服 + 客户端伴侣 + ./gradlew verifyAcceptanceReport
```

Forge 1.12.2：**禁止** Forge 服务端 mod；真服走 CatServer R5：

```bash
./gradlew :runRealServerAcceptanceCatServer
# 客户端伴侣：./platform/forge/1.12.2/gradlew --no-daemon prepareClientCompanionArtifacts
```

## 4. 脚手架换名

```bash
./gradlew renameScaffold \
  -Pmpmt.scaffold.id=mygame \
  -Pmpmt.scaffold.group=com.example.mygame \
  -Pmpmt.scaffold.name=MyGame \
  -Pmpmt.scaffold.dryRun=true
```

见 [`../tools/README.md`](../tools/README.md)。纯 kts，无需 python。
