# realserver-acceptance（约定插件）

Gradle 编排真服验收（禁 sh）。实例挂到应用插件的平台构建；逻辑在本 included build。

## 常用命令（路径已对齐每版本工程）

```bash
# Paper 宿主 + Fabric 1.20.1 客户端（异构）
./gradlew :platform-bukkit:server-1.20.1:ensurePaperRealServerHost \
  -Pmpmt.realserver.autoHost=true -Pmpmt.realserver.waitForReport=true

./gradlew -p platform-fabric-1.20.1 runAcceptanceClient \
  -Pmpmt.acceptance.server=127.0.0.1:25599

./gradlew :platform-bukkit:server-1.20.1:runRealServerAcceptance \
  -Pmpmt.realserver.autoHost=true
```

车道表见 `PlatformLane` / `PlatformLaneCatalog`（includeBuild 名为 `platform-fabric-1.20.1` 等）。
