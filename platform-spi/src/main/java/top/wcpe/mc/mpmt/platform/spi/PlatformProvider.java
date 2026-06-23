package top.wcpe.mc.mpmt.platform.spi;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;

/**
 * 平台访问点（Holder）：启动期一次性装配、之后只读（ADR-0002）。
 *
 * <p>{@link #boot} 驱动装配——发现唯一活跃平台 → 调其 {@code assemble} 把端口注入 L1 运行时 → 固化平台标识与
 * {@link FeatureGate}。装配后实例不可变、不承载可变业务状态（防静态可变单例滥用）。重复 boot 失败快。
 */
public final class PlatformProvider {

    private static volatile PlatformProvider instance;

    private final String platformId;
    private final FeatureGate featureGate;

    private PlatformProvider(String platformId, FeatureGate featureGate) {
        this.platformId = platformId;
        this.featureGate = featureGate;
    }

    /**
     * 装配并安装平台访问点：发现唯一活跃平台、注入端口、固化只读状态。
     *
     * @param classLoader 用于 ServiceLoader 发现的类加载器（须为承载平台 services 的加载器）
     * @param runtime     待注入端口的运行时
     * @return 安装后的访问点
     * @throws PlatformAssemblyException 重复 boot、零 / 多平台、或装配产物非法
     */
    public static synchronized PlatformProvider boot(ClassLoader classLoader, MpmtRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime 不能为空");
        if (instance != null) {
            throw new PlatformAssemblyException("平台已装配，禁止重复 boot（当前平台：" + instance.platformId + "）");
        }
        PlatformBootstrap bootstrap = PlatformAssembler.discover(classLoader);
        bootstrap.assemble(runtime.ports());
        String id = requireAssembled(bootstrap.platformId(), "平台 id 不能为空");
        FeatureGate gate = requireAssembled(bootstrap.featureGate(), "平台 FeatureGate 不能为空");
        instance = new PlatformProvider(id, gate);
        return instance;
    }

    private static <T> T requireAssembled(T value, String message) {
        if (value == null) {
            throw new PlatformAssemblyException(message);
        }
        return value;
    }

    /** 取当前平台访问点；未装配则失败快。 */
    public static PlatformProvider get() {
        PlatformProvider current = instance;
        if (current == null) {
            throw new IllegalStateException("平台尚未装配：请先调用 PlatformProvider.boot");
        }
        return current;
    }

    /** 是否已装配。 */
    public static boolean isBooted() {
        return instance != null;
    }

    /** 平台标识。 */
    public String platformId() {
        return platformId;
    }

    /** 能力探测器。 */
    public FeatureGate featureGate() {
        return featureGate;
    }

    /** 仅供测试重置静态 Holder；生产代码禁止调用。 */
    static void resetForTesting() {
        instance = null;
    }
}
