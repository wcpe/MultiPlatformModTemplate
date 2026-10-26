package top.wcpe.mc.mpmt.examples.counter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.feature.RuntimeContext;

/**
 * 服务端安装点注册表（示范域 counter）。
 *
 * <p>{@code server-internal} 允许的两种形态之一：安装点 object（另一个是 interface，ADR-0030）。
 * 它只保存装配动作，不含任何具体服务端实现。
 */
public final class CounterServerInstallers {

    private static final List<CounterServerInstallPoint> INSTALLERS = new ArrayList<>();

    private CounterServerInstallers() {
        // 工具类不实例化
    }

    /**
     * 注册一个服务端安装点。
     *
     * @param installer 安装点，不可为空
     */
    public static synchronized void register(CounterServerInstallPoint installer) {
        INSTALLERS.add(Objects.requireNonNull(installer, "安装点不能为空"));
    }

    /**
     * 依次执行已注册的安装点。
     *
     * <p>遍历前先复制快照：安装点自身可能再注册新的安装点，直接遍历会抛
     * {@code ConcurrentModificationException}。
     *
     * @param context 运行时上下文
     * @return 本次执行的安装点数量
     */
    public static synchronized int installAll(RuntimeContext context) {
        Objects.requireNonNull(context, "运行时上下文不能为空");
        List<CounterServerInstallPoint> snapshot = new ArrayList<>(INSTALLERS);
        snapshot.forEach(installer -> installer.install(context));
        return snapshot.size();
    }

    /** 清空注册表（仅供测试隔离使用）。 */
    static synchronized void clearForTest() {
        INSTALLERS.clear();
    }
}
