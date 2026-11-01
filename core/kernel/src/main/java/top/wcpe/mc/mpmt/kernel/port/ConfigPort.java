package top.wcpe.mc.mpmt.kernel.port;

import java.util.Optional;

/**
 * 配置读取端口（L0）：把平台配置目录下的一个文件读成给定的类型化模型。
 *
 * <p>为什么是端口而不是直接依赖 {@code core-config}（ADR-0030 决策 5）：
 * 域只允许依赖 {@code :core:kernel}，而配置加载要带第三方库（snakeyaml）与文件 I/O，
 * 那是基础设施。域只声明「我要一份类型化配置」，具体从哪里读、读什么格式由平台决定——
 * 与 {@link PersistencePort} 完全同构。
 *
 * <p>实现方（L1 {@code core-config}）把 {@code fileName} 解析到平台配置目录下的文件，
 * 按扩展名选格式（{@code .yml} / {@code .yaml} / {@code .json}）。
 */
public interface ConfigPort {

    /**
     * 读取一份配置。
     *
     * @param fileName 配置文件名（含扩展名，如 {@code counter.yml}），相对平台配置目录
     * @param type     目标类型
     * @param <T>      目标类型
     * @return 文件不存在时为空；存在但内容非法时抛异常（不静默吞）
     */
    <T> Optional<T> read(String fileName, Class<T> type);
}
