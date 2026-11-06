package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;

/**
 * counter 域的服务端配置加载（示范域）。
 *
 * <p>只经 {@link ConfigPort}（L0 端口）读配置：域看不到文件路径、也看不到 snakeyaml——
 * 具体从哪个目录读、用什么格式由平台决定（ADR-0030 决策 5：域外依赖只有 {@code :core:kernel}）。
 *
 * <p>缺文件回缺省值；**文件存在但内容非法则让异常向外招**，不静默回落（吞异常反模式）。
 */
public final class CounterConfigs {

    /** 约定文件名：平台配置目录下的 {@code counter.yml}（也可用 .yaml / .json）。 */
    public static final String FILE_NAME = "counter.yml";

    private final ConfigPort configPort;

    /**
     * 构造。
     *
     * @param configPort 配置读取端口，不可为空
     */
    public CounterConfigs(ConfigPort configPort) {
        this.configPort = Objects.requireNonNull(configPort, "配置端口不能为空");
    }

    /**
     * 首次启动时把带中文注释的缺省配置落在用户面前（幂等，已存在则一字不改）。
     *
     * <p>为什么要在读取前先写：只读不写的话，用户拿到的是一份“什么都没有”的目录，
     * 想知道默认值就得去翻代码。落一份带注释的文件，改配置就不再需要读源码。
     *
     * <p>模板来自本域自己的 jar（{@code mpmt-defaults/counter.yml}），不属于基础设施模块。
     *
     * @return 本次是否新建了文件（已存在为 {@code false}）
     */
    public boolean writeDefaultIfAbsent() {
        return configPort.writeDefault(FILE_NAME);
    }

    /**
     * 读取约定文件名的配置；不存在时返回缺省配置。
     *
     * @return 已归一的配置，不为空
     */
    public CounterConfig loadOrDefault() {
        return loadOrDefault(FILE_NAME);
    }

    /**
     * 读取指定文件名的配置；不存在时返回缺省配置。
     *
     * @param fileName 配置文件名（含扩展名），不可为空
     * @return 已归一的配置，不为空
     */
    public CounterConfig loadOrDefault(String fileName) {
        Objects.requireNonNull(fileName, "配置文件名不能为空");
        return configPort.read(fileName, CounterConfig.class).map(CounterConfig::normalized).orElseGet(CounterConfig::new);
    }
}
