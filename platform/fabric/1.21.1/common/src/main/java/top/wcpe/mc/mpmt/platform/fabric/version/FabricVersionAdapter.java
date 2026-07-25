package top.wcpe.mc.mpmt.platform.fabric.version;

/** 当前构建目标的 Fabric L4 适配器工厂入口。 */
public interface FabricVersionAdapter {

    String minecraftVersion();

    FabricClientNetwork clientNetwork(FabricChannel channel);

    FabricServerNetwork serverNetwork(FabricChannel channel);

    FabricHud hud();
}
