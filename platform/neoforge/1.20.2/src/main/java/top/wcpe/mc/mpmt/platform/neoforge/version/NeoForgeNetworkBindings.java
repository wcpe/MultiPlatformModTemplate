package top.wcpe.mc.mpmt.platform.neoforge.version;

import java.util.Objects;
import top.wcpe.mc.mpmt.platform.neoforge.version.v1_20_2.V1_20_2ServerNetwork;

/** NeoForge 网络版本绑定的唯一装配点。 */
public final class NeoForgeNetworkBindings {

    private static final String CHANNEL_NAMESPACE = "mpmt";
    private static final String CHANNEL_PATH = "main";

    private NeoForgeNetworkBindings() {
        // 工具类不实例化
    }

    /** 构造所选锚点的服务端网络适配器。 */
    public static NeoForgeServerNetwork serverNetwork(SupportedVersion version) {
        Objects.requireNonNull(version, "version 不能为空");
        switch (version) {
            case V1_20_2:
                return new V1_20_2ServerNetwork(CHANNEL_NAMESPACE, CHANNEL_PATH);
            default:
                throw new IllegalStateException("缺少 NeoForge 服务端网络适配：" + version);
        }
    }
}
