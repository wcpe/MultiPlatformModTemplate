# realserver-acceptance（约定插件）

Gradle 编排真服验收（禁 sh）。实例挂到应用插件的平台构建；逻辑在本插件工程（Gradle 插件工程经
根 `pluginManagement` 的 includeBuild 引入，属构建基础设施——平台车道本身是根构建子模块，ADR-0026）。

## 常用命令（路径已对齐根构建工程路径）

```bash
# Paper 宿主 + Fabric 1.20.1 客户端（异构）
./gradlew :platform:bukkit:1.20.1:ensurePaperRealServerHost \
  -Pmpmt.realserver.autoHost=true -Pmpmt.realserver.waitForReport=true

./gradlew :platform:fabric:fabric-1.20.1:runAcceptanceClient \
  -Pmpmt.acceptance.server=127.0.0.1:25599

./gradlew :platform:bukkit:1.20.1:runRealServerAcceptance \
  -Pmpmt.realserver.autoHost=true
```

车道表见 `PlatformLane` / `PlatformLaneCatalog`（每条的 `rootTaskPath` 形如
`:platform:fabric:fabric-1.20.1:runRealServerAcceptance`）。全部 Gradle 调用须在仓库根执行，
且根构建须以 **JDK 25** 守护运行（ADR-0026 决策 4）。

> 独立调用本插件工程（`./gradlew -p build-logic/realserver-acceptance …`）时守护 JVM 由
> `gradle/gradle-daemon-jvm.properties` 固定为 **JDK 21**（工具链同为 21）：detekt 1.23 的内嵌 Kotlin
> 编译器无法在 JDK 25 上运行，本工程的 ktlint / detekt 门禁因此只能跑在 ≤ 24 的 JVM 上（ADR-0027）。
> 作为根构建的 includeBuild 使用时不受影响。
