# 脚手架工具

## `init.sh` — 一键初始化（零 JDK 依赖）

克隆 / Use this template 后，把模板身份改成你的项目。在**仓库根**执行：

```bash
# 交互式（逐项提问）
./init.sh

# 非交互（用于脚本 / CI）
./init.sh --id mygame --group com.example.mygame --name MyGame

# 先预览不写盘
./init.sh --dry-run --id mygame --group com.example.mygame --name MyGame

# 同时改协议通道（互通双方须同一通道）
./init.sh --id mygame --group com.example.mygame --name MyGame --rewrite-channels
```

| 参数 | 默认（模板） | 含义 |
|------|----------------|------|
| `--id` | `mpmt` | 短 id、modId、产物前缀 `*-bukkit` |
| `--group` | `top.wcpe.mc.mpmt` | Maven group + Java 包根 |
| `--name` | `MultiPlatformModTemplate` | 展示名 / 插件 name |
| `--rewrite-channels` | 关 | 开启则改 `mpmt:main` 等通道（产品化时用） |
| `--dry-run` | 关 | 只预览不写盘 |
| `--yes` / `-y` | 关 | 非交互式，缺参数直接报错 |

**前置要求**：`bash` + `perl` + 常用 coreutils（Git Bash / macOS / Linux 自带）。**不需要 JDK、不需要 Gradle**——不必先让整个多平台构建配置成功，就能完成换名。
换名范围含 `build-logic` 的 Kotlin 包目录（`java/` 与 `kotlin/` 都搬）。

### 不做的事

- 不改 git 历史
- 不扫 `build/`、`.gradle/`、`.tmp/`
- 不自动提交
- 不删自身（换名后提示你手动 `rm init.sh`）

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
Forge 1.12.2 / 1.21.1 车道的产物已构建时一并捞入，否则仅打印命令。
