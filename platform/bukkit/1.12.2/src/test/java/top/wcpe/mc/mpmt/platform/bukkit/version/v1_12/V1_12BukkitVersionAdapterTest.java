package top.wcpe.mc.mpmt.platform.bukkit.version.v1_12;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitSchedulerPort;

/** Bukkit 1.12.2 L4 适配器的传统调度契约。 */
class V1_12BukkitVersionAdapterTest {

    @Test
    @DisplayName("1.12 适配器始终使用普通调度并委托全局任务")
    void 使用传统调度器() {
        AtomicReference<Plugin> scheduledPlugin = new AtomicReference<>();
        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        BukkitScheduler scheduler = scheduler(scheduledPlugin, scheduledTask);
        Plugin plugin = plugin(scheduler);
        V1_12BukkitVersionAdapter adapter = new V1_12BukkitVersionAdapter();

        SchedulerPort port = adapter.createScheduler(plugin, true);
        Runnable task = () -> {};
        adapter.executeGlobal(plugin, task);

        assertTrue(port instanceof BukkitSchedulerPort);
        assertSame(plugin, scheduledPlugin.get());
        assertSame(task, scheduledTask.get());
    }

    private static BukkitScheduler scheduler(
            AtomicReference<Plugin> scheduledPlugin, AtomicReference<Runnable> scheduledTask) {
        return proxy(
                BukkitScheduler.class,
                (ignored, method, args) -> {
                    if ("runTask".equals(method.getName())) {
                        scheduledPlugin.set((Plugin) args[0]);
                        scheduledTask.set((Runnable) args[1]);
                        return null;
                    }
                    throw new AssertionError("不应调用调度器方法：" + method.getName());
                });
    }

    private static Plugin plugin(BukkitScheduler scheduler) {
        Server server =
                proxy(
                        Server.class,
                        (ignored, method, args) -> {
                            if ("getScheduler".equals(method.getName())) {
                                return scheduler;
                            }
                            throw new AssertionError("不应调用服务端方法：" + method.getName());
                        });
        return proxy(
                Plugin.class,
                (ignored, method, args) -> {
                    if ("getServer".equals(method.getName())) {
                        return server;
                    }
                    throw new AssertionError("不应调用插件方法：" + method.getName());
                });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
