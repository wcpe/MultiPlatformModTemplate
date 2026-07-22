package top.wcpe.mc.mpmt.core.domain.port;

import java.util.List;
import java.util.Optional;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/**
 * 世界查询端口（L0）：只暴露平台无关的世界引用与字符串标识。
 *
 * <p>MVP 仅提供加载状态、已加载列表与引用解析，不扩展方块或区块操作。
 */
public interface WorldPort {

    /** 判断指定世界当前是否已加载。 */
    boolean isLoaded(String worldId);

    /** 返回当前已加载世界的快照。 */
    List<WorldRef> loadedWorlds();

    /** 把字符串标识解析为当前可用的世界引用；不可解析时返回空。 */
    Optional<WorldRef> resolve(String worldId);
}
