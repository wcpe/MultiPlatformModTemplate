# 脚手架工具

## `renameScaffold` — 一键换名（纯 kts）

克隆本模板后，把模板身份改成你的项目。实现：`gradle/scaffold-rename.gradle.kts`（无 python 依赖）。

| 参数 | 默认（模板） | 含义 |
|------|----------------|------|
| `-Pmpmt.scaffold.id` | `mpmt` | 短 id、modId、产物前缀 `*-bukkit` |
| `-Pmpmt.scaffold.group` | `top.wcpe.mc.mpmt` | Maven group + Java 包根 |
| `-Pmpmt.scaffold.name` | `MultiPlatformModTemplate` | 展示名 / 插件 name |
| `-Pmpmt.scaffold.rewriteChannels` | `false` | `true` 则改 `mpmt:main` 等通道（产品化时用） |
| `-Pmpmt.scaffold.dryRun` | `false` | `true` 只预览不写盘 |

```bash
# 先预览
./gradlew renameScaffold \
  -Pmpmt.scaffold.id=mygame \
  -Pmpmt.scaffold.group=com.example.mygame \
  -Pmpmt.scaffold.name=MyGame \
  -Pmpmt.scaffold.dryRun=true

# 写盘（建议干净 git 工作区）
./gradlew renameScaffold \
  -Pmpmt.scaffold.id=mygame \
  -Pmpmt.scaffold.group=com.example.mygame \
  -Pmpmt.scaffold.name=MyGame

# 同时改协议通道（互通双方须同一通道）
./gradlew renameScaffold \
  -Pmpmt.scaffold.id=mygame \
  -Pmpmt.scaffold.group=com.example.mygame \
  -Pmpmt.scaffold.name=MyGame \
  -Pmpmt.scaffold.rewriteChannels=true
```

### 不做的事

- 不改 git 历史
- 不扫 `build/`、`.gradle/`、`.tmp/`
- 不自动提交
- 不依赖本机 python

### 换名后

```bash
./gradlew --no-daemon :core:domain:compileJava :platform:bukkit:common:compileJava
./gradlew --no-daemon :verifyVersionMatrixBuild   # 可选，较慢
```

## 发布产物聚合

```bash
./gradlew :collectReleaseArtifacts
# 或
./gradlew :buildAll
```

输出：`build/dist/{bukkit,fabric,forge,neoforge,sponge}/`。  
Forge 1.12 / 1.21 独立 launcher 产物若已构建会一并捞入，否则仅打印命令。
