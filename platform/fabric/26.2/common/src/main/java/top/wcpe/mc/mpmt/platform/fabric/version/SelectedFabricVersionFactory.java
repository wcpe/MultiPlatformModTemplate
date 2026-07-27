package top.wcpe.mc.mpmt.platform.fabric.version;

import top.wcpe.mc.mpmt.platform.fabric.version.v26_2.V26_2FabricVersionAdapter;

/** 当前 26.2 构建唯一可见的 L4 工厂。 */
public final class SelectedFabricVersionFactory {

    private SelectedFabricVersionFactory() {
    }

    public static FabricVersionAdapter create() {
        return V26_2FabricVersionAdapter.INSTANCE;
    }
}
