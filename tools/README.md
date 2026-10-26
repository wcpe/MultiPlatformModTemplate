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
./gradlew --no-daemon :core:kernel:compileJava :platform:bukkit:common:compileJava
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

## `socks2http.py` — 本地 HTTP 代理转上游 SOCKS5（出站受限环境用）

Gradle / JVM **无法**经系统属性给 SOCKS5 传用户名密码（`-Djava.net.socks.username`
实测无效，报 `SOCKS : authentication failed`）。本脚本在本地起一个免认证 HTTP 代理，
把 CONNECT 隧道转发到带认证的上游 SOCKS5，让 JVM 只认本地 HTTP 代理。

典型用途：`maven.neoforged.net` 在部分网络下直连与经普通 HTTP 代理均被 reset，
neoforge 车道的 neoform / neoforge 制品拉不下来；换成能出境的 SOCKS5 后经本脚本中转即可。

```bash
export MPMT_PROXY_SOCKS_HOST=<上游主机>
export MPMT_PROXY_SOCKS_PORT=<上游端口>
export MPMT_PROXY_USER=<用户名>
export MPMT_PROXY_PASSWORD=<密码>
setsid python3 tools/socks2http.py > .tmp/socks2http.log 2>&1 < /dev/null &
```

| 变量 | 必填 | 说明 |
|---|---|---|
| `MPMT_PROXY_SOCKS_HOST` | 是 | 上游 SOCKS5 主机 |
| `MPMT_PROXY_SOCKS_PORT` | 是 | 上游 SOCKS5 端口 |
| `MPMT_PROXY_USER` | 是 | SOCKS5 用户名 |
| `MPMT_PROXY_PASSWORD` | 是 | SOCKS5 密码 |
| `MPMT_PROXY_LISTEN_PORT` | 否 | 本地监听端口，默认 `24081` |

**脚本不含任何凭据**，全部经环境变量传入。只监听 `127.0.0.1`，不暴露到局域网。
随后把 Gradle 的 `http(s).proxyHost/Port` 指向 `127.0.0.1:$MPMT_PROXY_LISTEN_PORT`
（通常写在 `~/.gradle/gradle.properties`，属本机配置，不入库）。
