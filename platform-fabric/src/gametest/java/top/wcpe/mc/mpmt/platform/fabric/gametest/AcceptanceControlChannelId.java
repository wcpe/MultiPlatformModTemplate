package top.wcpe.mc.mpmt.platform.fabric.gametest;

import net.minecraft.resources.ResourceLocation;

/** 验收测试控制通道 id（ADR-0014）：服务端接入与客户端伴侣共用同一通道，避免双处定义漂移。 */
public final class AcceptanceControlChannelId {

    /** 独立 test 通道，与产品通道 {@code mpmt:main} 分离。 */
    public static final ResourceLocation CHANNEL = new ResourceLocation("mpmt-test", "acceptance");

    private AcceptanceControlChannelId() {
        // 常量类不实例化
    }
}
