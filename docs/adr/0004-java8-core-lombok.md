# ADR-0004：核心层 Java 8 + Lombok，平台胶水随各 loader JDK

## 状态
已接受

## 背景
用户要求"全量 Java 8 + Lombok，因兼容性最广"。但存在硬约束：现代 Minecraft（及 Fabric 1.18+ / NeoForge）被 Mojang 强制要求用更高 JDK（Java 17 / 21）编译运行，**无法用 Java 8 编译**。需要在"最大兼容"与"现代平台现实"之间取舍。

## 决策
- **L0–L2（平台无关核心：core-domain / core-runtime / core-server / core-client / protocol / platform-spi）严格编译为 Java 8 字节码**——以最大化兼容，连 1.12 Forge（Java 8）都能加载。
- **L3/L4 平台胶水按各 loader 的最低 JDK 编译**（如 1.20.1 三平台需 Java 17，未来 NeoForge 需 Java 21），但仍依赖 Java 8 的核心产物。
- **Lombok 仅用于 Java 模块**（领域模型、DTO 等），减少样板。

## 理由
- 核心 Java 8 → 老版本/老平台可用，最大化"一份逻辑跑遍"的覆盖面。
- 胶水随 loader → 不和 Mojang 的 JDK 要求对抗，现代平台正常工作。
- 核心是 Java 8 字节码，高 JDK 的胶水可正常依赖（向下兼容），方向成立。

## 后果
- 正面：核心兼容面最大；样板代码少。
- 负面：与"全量 Java 8"的字面要求有偏差（已与用户对齐，按本决策执行）；多 JDK 工具链增加构建配置复杂度（toolchain 按模块设定）。
- 约束：L0–L2 不得使用 Java 9+ 语法/API；CI / 构建须对核心模块锁定 `sourceCompatibility = 8`。Lombok 不得泄漏到对外 API 的语义（编译期注解，运行期无依赖）。
- 约束（评审，必须）：**仅锁 `sourceCompatibility` 不够**——它只保证字节码版本，不保证 API 存在性。须对 L0–L2 用 **`javac --release 8`**（JDK 11+ 经 ct.sym 限制只能引用 JDK 8 API、编译期即报错）或 `animal-sniffer` 校验；否则误用 Java 9+ API（`List.of` / `Optional.isEmpty` / `String.isBlank` 等）能编过、却在 1.12.2（Java 8 运行）`NoSuchMethodError`，且错误延迟到 P2 才暴露。写入 `static-analysis.md`。

## 备选方案
- **全量 Java 8**：现代版本根本无法编译运行——技术上不可行，否决。
- **全量高 JDK（如 17）**：放弃 1.12 等老平台兼容，背离"兼容性最广"目标——否决。
- **不用 Lombok**：样板更多；用户明确要求用 Lombok——否决。
