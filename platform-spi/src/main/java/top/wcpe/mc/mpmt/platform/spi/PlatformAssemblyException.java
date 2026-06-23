package top.wcpe.mc.mpmt.platform.spi;

/**
 * 平台装配异常：零平台、我方多入口同时激活、或装配产物非法时抛出，用于"启动期失败快"（ADR-0002 / ADR-0008）。
 */
public class PlatformAssemblyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlatformAssemblyException(String message) {
        super(message);
    }
}
