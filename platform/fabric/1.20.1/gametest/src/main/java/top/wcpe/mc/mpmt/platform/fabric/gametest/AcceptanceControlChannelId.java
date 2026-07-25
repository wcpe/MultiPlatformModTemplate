package top.wcpe.mc.mpmt.platform.fabric.gametest;

import top.wcpe.mc.mpmt.platform.fabric.version.FabricChannel;

/** 验收控制通道的无 MC 类型单一真源（ADR-0014）。 */
public final class AcceptanceControlChannelId {

    /** 独立 test 通道，与产品通道 {@code mpmt:main} 分离。 */
    public static final FabricChannel CHANNEL = new FabricChannel("mpmt-test", "acceptance");

    private AcceptanceControlChannelId() {
        // 常量类不实例化
    }
}
