package top.wcpe.mc.mpmt.core.domain;

/**
 * MPMT 核心常量。
 *
 * <p>项目命名空间根，跨端协议通道（如 {@code mpmt:main}）与资源路径前缀共用，
 * 是 L0 内零平台依赖的纯常量定义。
 */
public final class Mpmt {

    /** 项目命名空间标识：协议通道命名空间、资源前缀等共用。 */
    public static final String NAMESPACE = "mpmt";

    private Mpmt() {
        // 常量类不实例化
    }
}
