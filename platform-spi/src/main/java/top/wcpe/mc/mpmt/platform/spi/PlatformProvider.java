package top.wcpe.mc.mpmt.platform.spi;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;

/**
 * 平台访问点（Holder）：启动期一次性装配、之后只读（ADR-0002）。
 *
 * <p>{@link #boot} 驱动装配——发现唯一活跃平台 → 调其 {@code assemble} 把端口注入 L1 运行时 → 固化平台标识与
 * {@link FeatureGate}。装配后实例不可变、不承载可变业务状态（防静态可变单例滥用）。重复 boot 失败快。
 *
 * <p><b>进程级单一活跃绑定</b>（ADR-0008 / FR-25）：本类静态 {@code instance} 只拦同类加载器的重复 boot；融合服
 * （CatServer 等 Forge+Bukkit 同进程）上我方 Bukkit 插件与 Forge mod 各自类加载器、各有一份本类静态，拦不住
 * "我方多入口同进程同时激活"。故再用 JVM 全局系统属性 {@link #ACTIVE_PLATFORM_PROPERTY} 跨类加载器把关——
 * 已有我方活跃绑定再激活第二入口即失败快（融合服上应只装一个我方入口，绑定 Bukkit 家族为唯一活跃平台）。
 */
public final class PlatformProvider {

    /**
     * 进程级"我方活跃平台已绑定"标记的系统属性键（跨类加载器可见）。值为已绑定的平台 id；
     * {@link #boot} 时若已存在即拒绝（多入口同时激活），{@link #deactivate} / 测试重置时清除。
     */
    static final String ACTIVE_PLATFORM_PROPERTY = "top.wcpe.mc.mpmt.active-platform";

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
        // 进程级跨类加载器把关（ADR-0008 / FR-25）：已有我方活跃绑定（如融合服上 Forge mod 入口先激活）即拒绝
        String activeInProcess = System.getProperty(ACTIVE_PLATFORM_PROPERTY);
        if (activeInProcess != null) {
            throw new PlatformAssemblyException(
                    "进程内已有我方活跃平台绑定（"
                            + activeInProcess
                            + "），禁止多入口同时激活——融合服上只装一个我方入口、绑定 Bukkit 家族为唯一活跃平台（ADR-0008）");
        }
        PlatformBootstrap bootstrap = PlatformAssembler.discover(classLoader);
        bootstrap.assemble(runtime.ports());
        String id = requireAssembled(bootstrap.platformId(), "平台 id 不能为空");
        FeatureGate gate = requireAssembled(bootstrap.featureGate(), "平台 FeatureGate 不能为空");
        // 装配成功后才置进程级标记（失败不留痕、可重试）
        System.setProperty(ACTIVE_PLATFORM_PROPERTY, id);
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

    /**
     * 关停期释放平台绑定（清本类静态实例 + 进程级标记），供平台入口在 disable / 卸载时调用，
     * 使同 JVM 内重新启用（如 Bukkit {@code /reload}）能再次 boot、不被进程级标记误拦。
     */
    public static synchronized void deactivate() {
        instance = null;
        System.clearProperty(ACTIVE_PLATFORM_PROPERTY);
    }

    /** 仅供测试重置静态 Holder（含进程级标记）；生产代码禁止调用。 */
    static void resetForTesting() {
        instance = null;
        System.clearProperty(ACTIVE_PLATFORM_PROPERTY);
    }
}
