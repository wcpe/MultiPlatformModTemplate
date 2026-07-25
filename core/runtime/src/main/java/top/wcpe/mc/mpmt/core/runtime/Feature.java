package top.wcpe.mc.mpmt.core.runtime;

/**
 * 玩法特性：一段平台无关的玩法逻辑单元，由 {@link MpmtRuntime} 在生命周期中启用 / 停用。
 *
 * <p>特性在 {@link #onEnable} 里经 {@link RuntimeContext} 订阅事件、请求端口能力；玩法逻辑只写在这一侧（L0/L1），
 * 平台差异由端口与 FeatureGate 关在接口之后。
 */
public interface Feature {

    /** 特性名（用于日志与去重）。 */
    String name();

    /** 启用：注册订阅、初始化状态等。 */
    void onEnable(RuntimeContext context);

    /** 停用：释放资源、取消订阅等。默认无操作。 */
    default void onDisable(RuntimeContext context) {
        // 默认无需停用逻辑
    }
}
