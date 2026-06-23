package top.wcpe.mc.mpmt.platform.forge.proxy;

/**
 * 端侧代理接口：Forge 的 client / server 分离代理共同契约（FR-09）。
 *
 * <p>装配（平台发现 / 端口注入）是两端共用逻辑，在 mod 主类一次完成；本接口只承载<b>端侧专有</b>初始化，
 * 由 {@code MpmtForgeMod} 按运行端选择 {@link ClientProxy} / {@link ServerProxy} 调用。
 */
public interface SidedProxy {

    /** 端侧专有初始化。 */
    void init();
}
