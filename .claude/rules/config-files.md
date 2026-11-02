# 配置文件规范

> 适用于面向运行期的 YAML 配置文件（`*.yml` / `*.yaml`），如各车道的 `src/main/resources/plugin.yml`、`e2e/harness/src/main/resources/config.yml` 等。

## 1. 文件命名（强制）

- 文件名用 **kebab-case**：全小写字母 + 数字，多词用 `-` 连接。
- 扩展名统一 `.yml`（除非上游框架强制 `.yaml`）。
- 禁止 `snake_case` / `camelCase` / `PascalCase` / 空格 / 中文做分隔。

## 2. 字段命名（强制）

- 所有 YAML 字段名用 **kebab-case**，每层嵌套都遵守。
- 例外：第三方框架强制字段（如 compose 的 `depends_on`、env 变量名）按上游约定。
- 例外：由 `core-config` 经 snakeyaml 按 **Java 字段名**映射的域配置（如 `counter.yml`）跟随字段名（camelCase）——键名就是代码里的字段名，改名会让两者脱钩。
- 字段值（枚举、ID、字符串）保留业务约定，不受约束。

## 3. 字段中文注释（强制）

- **每个字段上方必须有中文行注释**，说明用途、取值范围、默认值、影响。
- 用大白话，让非技术运维看懂；注释独占一行写在字段上方（不写行尾）。

### 示例

✅ 正确
```yaml
health:
  # 下发给节点的心跳周期（秒）
  heartbeat-interval-sec: 10
  # 超过多少秒未收到心跳即判失联
  ttl-sec: 30
```

❌ 错误（缺注释 / 注释空洞 / 行尾注释）
```yaml
health:
  heartbeat-interval-sec: 10   # interval
  ttl-sec: 30
```

## 4. 文件头部说明（建议）

每个配置文件头部保留 1-3 行中文整体说明：这文件是干什么的、由哪个组件加载。

```yaml
# 应用自身配置（非业务配置）。由配置加载模块读取，env 覆盖优先。
```

## 5. 安全

- 敏感项（DB 密码、token）不写进入库的 yaml，走环境变量；仓库只放 `.env.example` 占位。

## 6. 与现有规则的关系

- 本规则是 `comments.md` 在配置文件场景的细化；冲突以本规则为准。
- 不约束代码侧字段名（各语言按自身约定）。
