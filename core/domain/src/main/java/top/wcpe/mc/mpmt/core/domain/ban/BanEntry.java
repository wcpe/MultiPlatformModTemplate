package top.wcpe.mc.mpmt.core.domain.ban;

import lombok.NonNull;
import lombok.Value;

/** 封禁条目：被封标识 + 原因。 */
@Value
public class BanEntry {

    @NonNull
    MachineCode code;

    @NonNull
    String reason;
}
