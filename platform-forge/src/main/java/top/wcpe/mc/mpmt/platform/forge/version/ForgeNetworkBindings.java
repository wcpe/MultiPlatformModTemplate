package top.wcpe.mc.mpmt.platform.forge.version;

import java.util.Objects;
import top.wcpe.mc.mpmt.platform.forge.version.v1_20.V1_20ServerNetwork;

/** Forge 网络版本绑定的唯一装配点。 */
public final class ForgeNetworkBindings {

    private static final String CHANNEL_NAMESPACE = "mpmt";
    private static final String CHANNEL_PATH = "main";

    private ForgeNetworkBindings() {
        // 工具类不实例化
    }

    /** 构造所选锚点的服务端网络适配器。 */
    public static ForgeServerNetwork serverNetwork(SupportedVersion version) {
        Objects.requireNonNull(version, "version 不能为空");
        switch (version) {
            case V1_20:
                return new V1_20ServerNetwork(CHANNEL_NAMESPACE, CHANNEL_PATH);
            default:
                throw new IllegalStateException("缺少 Forge 服务端网络适配：" + version);
        }
    }
}
