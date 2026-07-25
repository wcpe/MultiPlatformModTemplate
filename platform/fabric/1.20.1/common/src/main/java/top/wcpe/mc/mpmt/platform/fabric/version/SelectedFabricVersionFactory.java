package top.wcpe.mc.mpmt.platform.fabric.version;

import top.wcpe.mc.mpmt.platform.fabric.version.v1_20.V1_20FabricVersionAdapter;

/** 当前 1.20.1 构建唯一可见的 L4 工厂。 */
public final class SelectedFabricVersionFactory {

    private SelectedFabricVersionFactory() {
    }

    public static FabricVersionAdapter create() {
        return V1_20FabricVersionAdapter.INSTANCE;
    }
}
