package top.wcpe.mc.mpmt.core.domain.ref;

import java.util.UUID;
import lombok.NonNull;
import lombok.Value;

/**
 * 玩家引用（L0 内核）：玩家的平台无关领域视图（UUID + 名称），不携带任何平台原生对象。
 *
 * <p>玩法逻辑经它标识玩家、向其发消息；触碰玩家状态的调度以其 UUID 构造 {@link EntityRef} 表达归属。
 */
@Value
public class PlayerRef {

    @NonNull
    UUID uuid;

    @NonNull
    String name;
}
