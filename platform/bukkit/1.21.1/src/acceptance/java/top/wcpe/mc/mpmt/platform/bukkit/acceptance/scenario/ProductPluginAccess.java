package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 验收源集访问产品插件 API 的运行期桥。
 *
 * <p>tip 的 acceptance 与产品分 jar（ADR-0014），编译 classpath 不挂 main；运行期两插件并存时
 * 经 {@link Bukkit#getPluginManager()} 取产品实例再反射调用。不得用验收类加载器 {@code Class.forName}
 * （跨插件类加载器会找不到产品主类）。返回值仅使用双方共享类型（JDK / Bukkit API），避免跨 CL 强转。
 */
final class ProductPluginAccess {

    private static final String PRODUCT_PLUGIN_NAME = "MultiPlatformModTemplate";

    private ProductPluginAccess() {
        // 工具类不实例化
    }

    /** 经真实产品 HudMessageService 下发 ACTIONBAR。 */
    static void sendActionBarHud(Player player, String text) {
        invoke("sendActionBarHud", new Class<?>[] {Player.class, String.class}, player, text);
    }

    /** 产品活跃平台 id。 */
    static String activePlatformId() {
        return (String) invoke("activePlatformId", new Class<?>[] {});
    }

    /** 产品是否启用 Forge+Bukkit 融合服能力。 */
    static boolean isHybridForgeBukkit() {
        return (Boolean) invoke("isHybridForgeBukkit", new Class<?>[] {});
    }

    /** 产品调度端口实现类全名。 */
    static String schedulerPortClassName() {
        return (String) invoke("schedulerPortClassName", new Class<?>[] {});
    }

    /** 经产品全局调度入口执行任务。 */
    static void runGlobalSchedulerTask(Runnable task) {
        invoke("runGlobalSchedulerTask", new Class<?>[] {Runnable.class}, task);
    }

    /** 经产品实体调度入口执行任务。 */
    static void runEntitySchedulerTask(UUID entityId, Runnable task) {
        invoke(
                "runEntitySchedulerTask",
                new Class<?>[] {UUID.class, Runnable.class},
                entityId,
                task);
    }

    /** 经产品区域调度入口执行任务。 */
    static void runLocationSchedulerTask(String worldId, int x, int z, Runnable task) {
        invoke(
                "runLocationSchedulerTask",
                new Class<?>[] {String.class, int.class, int.class, Runnable.class},
                worldId,
                x,
                z,
                task);
    }

    private static Object invoke(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Plugin product = productPlugin();
            Method method = product.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(product, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("调用产品 API " + methodName + " 失败", error);
        }
    }

    private static Plugin productPlugin() {
        Plugin product = Bukkit.getPluginManager().getPlugin(PRODUCT_PLUGIN_NAME);
        if (product == null || !product.isEnabled()) {
            throw new IllegalStateException("产品插件 " + PRODUCT_PLUGIN_NAME + " 未启用");
        }
        return product;
    }
}
