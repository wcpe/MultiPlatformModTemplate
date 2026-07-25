package top.wcpe.mc.mpmt.platform.fabric.version;

import top.wcpe.mc.mpmt.platform.fabric.version.v1_21.V1_21FabricVersionAdapter;

/** 当前 1.21.1 构建唯一可见的 L4 工厂。 */
public final class SelectedFabricVersionFactory {

    private SelectedFabricVersionFactory() {
    }

    public static FabricVersionAdapter create() {
        return V1_21FabricVersionAdapter.INSTANCE;
    }
}
