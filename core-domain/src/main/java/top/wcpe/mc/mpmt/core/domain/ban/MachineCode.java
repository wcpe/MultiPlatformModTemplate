package top.wcpe.mc.mpmt.core.domain.ban;

import lombok.NonNull;
import lombok.Value;

/**
 * 弱客户端标识值对象：封装客户端上报的标识字符串（如 SHA-256 hex）。
 *
 * <p>仅为不可信弱标签（可伪造 / 可缺席 / 同机可变异机可撞），封禁以此为键作威慑、非安全保证（见 SECURITY.md）。
 */
@Value
public class MachineCode {

    @NonNull
    String value;
}
