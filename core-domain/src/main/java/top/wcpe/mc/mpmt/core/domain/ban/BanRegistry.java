package top.wcpe.mc.mpmt.core.domain.ban;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 封禁表（L0 纯逻辑，线程安全）：按弱客户端标识封禁 / 解封 / 查询。
 *
 * <p>命中即拦截"诚实上报且匹配"者——封禁为威慑、非安全保证（标识不可信，见 SECURITY.md）。
 * 命令 / 持久化等副作用在 L1/L3 完成；本类只持有内存封禁集，可被命令线程与网络线程并发读写。
 */
public final class BanRegistry {

    private final Map<MachineCode, BanEntry> bans = new ConcurrentHashMap<>();

    /** 封禁某标识（重复封禁覆盖原因）。 */
    public void ban(MachineCode code, String reason) {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(reason, "reason 不能为空");
        bans.put(code, new BanEntry(code, reason));
    }

    /** 解封某标识（未封禁则无操作）。 */
    public void unban(MachineCode code) {
        Objects.requireNonNull(code, "code 不能为空");
        bans.remove(code);
    }

    /** 是否被封禁。 */
    public boolean isBanned(MachineCode code) {
        Objects.requireNonNull(code, "code 不能为空");
        return bans.containsKey(code);
    }

    /** 当前全部封禁条目（快照）。 */
    public List<BanEntry> list() {
        return new ArrayList<>(bans.values());
    }
}
