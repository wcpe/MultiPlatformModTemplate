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

    /**
     * 把一份<b>缺省配置</b>写到平台配置目录（仅当文件不存在时）。
     *
     * <p>为什么需要它：{@link #read} 在文件不存在时返回空，调用方要么用代码里的缺省值、
     * 要么就得自己拼路径去写——后者会让域重新碰上文件路径，与端口设计相悖。
     * 有了本方法，“首次启动把带中文注释的缺省文件落在用户面前”成为一调调用，
     * 用户能直接在磁盘上改，而不是去翻代码找默认值。
     *
     * <p><b>幂等</b>：文件已存在则<b>一字不改</b>（不能拿默认值覆盖用户改过的配置），
     * 返回 {@code false}；只有真正新建时才返回 {@code true}。
     *
     * <p>写入的格式与字段顺序由实现决定（当前为带中文注释的 UTF-8 文本）。
     * 实现必须保证：写入的文件能被 {@link #read} 以同名参数读回。
     *
     * @param fileName 配置文件名（含扩展名，如 {@code counter.yml}），相对平台配置目录
     * @return 本次是否新建了文件（已存在为 {@code false}）
     * @throws java.io.UncheckedIOException 写入失败
     */
    boolean writeDefault(String fileName);
}
