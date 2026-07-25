package top.wcpe.mc.mpmt.core.domain.port;

import java.util.Optional;

/**
 * 持久化端口（L0）：玩法状态的读写持久化，具体存储（文件 / 数据库）由平台或宿主决定，L0 不感知。
 *
 * <p>以 {@code namespace + key} 定位一条字符串值；调用方自行序列化复杂结构为字符串。
 * 持久化通常涉及阻塞 IO，调用方应经 {@code SchedulerPort.runAsync} 在异步线程读写、不占用主线程 / tick。
 */
public interface PersistencePort {

    /** 读取一条记录，不存在返回空。 */
    Optional<String> read(String namespace, String key);

    /** 写入 / 覆盖一条记录。 */
    void write(String namespace, String key, String value);
}
