package top.wcpe.mc.mpmt.core.domain.ban;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 封禁表（L0 纯逻辑，线程安全）：按弱客户端标识封禁 / 解封 / 查询。
 *
 * <p>命中即拦截"诚实上报且匹配"者——封禁为威慑、非安全保证（标识不可信，见 SECURITY.md）。
 * 命令 / 持久化等副作用在 L1/L3 完成；本类只持有内存封禁集，可被命令线程与网络线程并发读写。
 */
public final class BanRegistry {

    private volatile Map<MachineCode, BanEntry> bans = Collections.emptyMap();

    /** 封禁某标识（重复封禁覆盖原因）。 */
    public synchronized void ban(MachineCode code, String reason) {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(reason, "reason 不能为空");
        Map<MachineCode, BanEntry> updated = new HashMap<>(bans);
        updated.put(code, new BanEntry(code, reason));
        bans = immutable(updated);
    }

    /** 解封某标识（未封禁则无操作）。 */
    public synchronized void unban(MachineCode code) {
        Objects.requireNonNull(code, "code 不能为空");
        if (!bans.containsKey(code)) {
            return;
        }
        Map<MachineCode, BanEntry> updated = new HashMap<>(bans);
        updated.remove(code);
        bans = immutable(updated);
    }

    /** 原子替换全部封禁条目。 */
    public synchronized void replaceAll(Collection<BanEntry> entries) {
        Objects.requireNonNull(entries, "entries 不能为空");
        Map<MachineCode, BanEntry> replacement = new HashMap<>();
        for (BanEntry entry : entries) {
            BanEntry value = Objects.requireNonNull(entry, "entry 不能为空");
            replacement.put(value.getCode(), value);
        }
        bans = immutable(replacement);
    }

    /** 查询某标识的封禁条目。 */
    public Optional<BanEntry> find(MachineCode code) {
        Objects.requireNonNull(code, "code 不能为空");
        return Optional.ofNullable(bans.get(code));
    }

    /** 是否被封禁。 */
    public boolean isBanned(MachineCode code) {
        return find(code).isPresent();
    }

    /** 当前全部封禁条目（按机器码排序的不可变快照）。 */
    public List<BanEntry> list() {
        List<BanEntry> snapshot = new ArrayList<>(bans.values());
        snapshot.sort(Comparator.comparing(entry -> entry.getCode().getValue()));
        return Collections.unmodifiableList(snapshot);
    }

    private static Map<MachineCode, BanEntry> immutable(Map<MachineCode, BanEntry> source) {
        return Collections.unmodifiableMap(source);
    }
}
