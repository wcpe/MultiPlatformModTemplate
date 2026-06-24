package top.wcpe.mc.mpmt.core.domain.port;

/**
 * 客户端标识提供者（L0 端口，客户端侧）：产出**弱客户端标识**（如基于弱硬件 / 系统属性的 SHA-256 hex）。
 *
 * <p>该标识可伪造、可随机化、同机可变异机可撞，仅作威慑性弱标签、非安全凭据（详见 SECURITY.md / network spec §3.5）。
 * 可插拔：默认实现在客户端公共逻辑，宿主可替换。
 */
public interface MachineCodeProvider {

    /** 返回弱客户端标识（十六进制字符串等）。 */
    String get();
}
