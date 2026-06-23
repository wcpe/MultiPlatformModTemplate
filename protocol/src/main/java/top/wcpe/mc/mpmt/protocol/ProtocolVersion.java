package top.wcpe.mc.mpmt.protocol;

/**
 * 协议版本与版本协商（单一真源，ADR-0006）。
 *
 * <p>{@link #CURRENT} 为本端当前协议版本；{@link #MIN_SUPPORTED} 为可兼容的最低对端版本。
 * 破坏性协议变更须递增 {@code CURRENT}、在 CHANGELOG + ADR 写明迁移，并维护 {@code MIN_SUPPORTED}。
 */
public final class ProtocolVersion {

    /** 本端当前协议版本号。 */
    public static final int CURRENT = 1;

    /** 可兼容的最低对端协议版本号。 */
    public static final int MIN_SUPPORTED = 1;

    private ProtocolVersion() {
        // 常量类不实例化
    }

    /**
     * 判断对端协议版本是否与本端兼容。
     *
     * <p>兼容条件：{@code MIN_SUPPORTED <= remoteVersion <= CURRENT}。低于下限说明对端过旧、
     * 高于上限说明对端过新（本端不认识其协议），两者都明确判为不兼容（握手时据此拒绝，不静默错乱）。
     *
     * @param remoteVersion 对端上报的协议版本
     * @return 兼容返回 true
     */
    public static boolean isCompatible(int remoteVersion) {
        return remoteVersion >= MIN_SUPPORTED && remoteVersion <= CURRENT;
    }
}
