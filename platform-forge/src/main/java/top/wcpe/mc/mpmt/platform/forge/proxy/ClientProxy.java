package top.wcpe.mc.mpmt.platform.forge.proxy;

import java.util.Objects;
import net.minecraftforge.network.event.EventNetworkChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientHudReceiver;

/**
 * Forge 客户端代理：客户端发行环境下的端侧初始化接缝（FR-09）。
 *
 * <p>本类<b>仅在 {@code Dist.CLIENT}</b> 被 {@code MpmtForgeMod} 实例化，故可安全引用客户端专有的
 * {@link ForgeClientHudReceiver}（不会被服务端加载）。{@link #init()} 在<b>同一</b>产品通道
 * （{@code mpmt:main}）上追加客户端 HUD 入站监听（FR-27）。
 */
public final class ClientProxy implements SidedProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    /** 产品通道（{@code mpmt:main}）实例，由 {@code ForgeServerTransport} 构造期注册并经 mod 主类传入。 */
    private final EventNetworkChannel productChannel;

    public ClientProxy(EventNetworkChannel productChannel) {
        this.productChannel = Objects.requireNonNull(productChannel, "productChannel 不能为空");
    }

    @Override
    public void init() {
        // 跨端 HUD 渲染（FR-27）：在产品通道上追加客户端入站监听收 S2C HUD 字节
        ForgeClientHudReceiver.register(productChannel);
        LOGGER.info("MPMT Forge 客户端代理就绪（已注册 HUD 收包）");
    }
}
