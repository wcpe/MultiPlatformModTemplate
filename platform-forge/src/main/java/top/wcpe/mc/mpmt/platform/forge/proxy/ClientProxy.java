package top.wcpe.mc.mpmt.platform.forge.proxy;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientHudReceiver;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeServerTransport;

/**
 * Forge 客户端代理：客户端发行环境下的端侧初始化接缝（FR-09）。
 *
 * <p>本类<b>仅在 {@code Dist.CLIENT}</b> 被 {@code MpmtForgeMod} 实例化，故可安全引用客户端专有的
 * {@link ForgeClientHudReceiver}（不会被服务端加载）。{@link #init()} 向产品传输注入客户端 HUD 收包处理器
 * （收 S2C HUD 字节后渲染，FR-27）。
 */
public final class ClientProxy implements SidedProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt");

    /** 产品服务端传输（持 {@code mpmt:main} SimpleChannel），由 mod 主类构造期传入。 */
    private final ForgeServerTransport transport;

    public ClientProxy(ForgeServerTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
    }

    @Override
    public void init() {
        // 跨端 HUD 渲染（FR-27）：向裸 payload 路由注册产品通道客户端收包，收 S2C HUD 字节后渲染（ADR-0018）
        ForgeClientHudReceiver.register(transport.channelId());
        LOGGER.info("MPMT Forge 客户端代理就绪（已注册 HUD 收包）");
    }
}
