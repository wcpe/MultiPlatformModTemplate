package top.wcpe.mc.mpmt.platform.forge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 测试用的假 {@code Minecraft} 客户端（Java 8 兼容）。
 *
 * <p>1.12.2 的 HUD 适配器在收包后调 {@code Minecraft.getMinecraft().addScheduledTask(...)} 把渲染推迟到
 * 客户端线程。纯 JVM 下 {@code getMinecraft()} 返回 {@code null}，故本类：
 *
 * <ol>
 *   <li>用 {@code sun.misc.Unsafe.allocateInstance} 造出未跑构造器的 {@link Minecraft}（其构造器会拉起整套
 *       客户端运行态，测试不可用）；
 *   <li>反射塞入真实的 {@code scheduledTasks} 队列与静态 {@code instance}，使 {@code addScheduledTask} 可用；
 *   <li>用 try-with-resources 还原静态 {@code instance}，不污染同 JVM 的其它测试。
 * </ol>
 *
 * <p>{@code player} / {@code ingameGUI} 保持 {@code null}：它们是具体类，纯 JUnit 无法在不引入 mock 库的前提下
 * 替身。因此本fakeClient覆盖"收包 → 写快照 → 调度渲染任务 → 渲染因玩家缺席而安全跳过"整条路径，
 * 渲染分支（title / chat / actionbar 的实际落点）不在单元测试范围内。
 */
public final class ForgeClientHarness implements AutoCloseable {

    /** 待执行的客户端线程任务（对应真实 {@code Minecraft.scheduledTasks}）。 */
    public final Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<Runnable>();

    private final Object originalStaticInstance;
    private final Minecraft fakeClient;

    private ForgeClientHarness(Object originalStaticInstance, Minecraft fakeClient) {
        this.originalStaticInstance = originalStaticInstance;
        this.fakeClient = fakeClient;
    }

    /** 装入fakeClient与假 FML 侧别委托。 */
    public static ForgeClientHarness install() {
        Minecraft fakeClient = 造客户端();
        ForgeClientHarness harness = new ForgeClientHarness(Minecraft.getMinecraft(), fakeClient);
        harness.装入运行态();
        return harness;
    }

    /**
     * 装入假 FML 侧别（{@code FMLCommonHandler.getSide()} 返回 {@link Side#CLIENT}）。
     *
     * <p>{@code FMLEventChannel} 的静态初始化会读 {@code getSide()}，而纯 JVM 下
     * {@code sidedDelegate} 为空导致 NPE。装上客户端侧代理后，{@code NetworkRegistry} 与
     * {@code ForgeClientTransport} 即可构造。
     *
     * <p>返回的句柄用于还原原值。
     */
    public static AutoCloseable 装入客户端侧别() {
        return 装入客户端侧别(new SidedHandlerRecorder());
    }

    /**
     * 装入假 FML 侧别并记录对 {@code IFMLSidedHandler} 的调用。
     *
     * <p>用于观察出站包是否真的到达 FML 侧的 {@code getClientToServerNetworkManager}（即"发给服务端"）。
     *
     * @return 记录器；其 {@link SidedHandlerRecorder#关闭()} 负责还原原值
     */
    public static SidedHandlerRecorder 装入可记录客户端侧别() {
        SidedHandlerRecorder recorder = new SidedHandlerRecorder();
        设置侧别委托(recorder);
        return recorder;
    }

    private static AutoCloseable 装入客户端侧别(SidedHandlerRecorder recorder) {
        设置侧别委托(recorder);
        return recorder;
    }

    private static void 设置侧别委托(SidedHandlerRecorder recorder) {
        try {
            Class<?> handlerClass = Class.forName("net.minecraftforge.fml.common.IFMLSidedHandler");
            Object delegate =
                    代理(
                            handlerClass,
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object source, Method method, Object[] args) {
                                    recorder.记录(method.getName());
                                    if (method.getName().equals("getSide")) {
                                        return Side.CLIENT;
                                    }
                                    return 默认值(source, method, args);
                                }
                            });
            Class<?> commonHandler = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler");
            Field field = commonHandler.getDeclaredField("sidedDelegate");
            field.setAccessible(true);
            Object instance = commonHandler.getMethod("instance").invoke(null);
            recorder.绑定(field, instance, field.get(instance));
            field.set(instance, delegate);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法装入 FML 客户端侧别", error);
        }
    }

    /** FML 侧别委托的invocationLog器，同时作为还原句柄。 */
    public static final class SidedHandlerRecorder implements AutoCloseable {
        /** 被调用过的方法名（按调用顺序）。 */
        public final List<String> invocationLog = new ArrayList<String>();

        private Field field;
        private Object instance;
        private Object previous;

        private void 绑定(Field field, Object instance, Object previous) {
            this.field = field;
            this.instance = instance;
            this.previous = previous;
        }

        private void 记录(String methodName) {
            invocationLog.add(methodName);
        }

        /** 是否调用过指定方法。 */
        public boolean 调用过(String methodName) {
            return invocationLog.contains(methodName);
        }

        /** 清空记录。 */
        public void 清空() {
            invocationLog.clear();
        }

        @Override
        public void close() {
            if (field == null) {
                return;
            }
            try {
                field.set(instance, previous);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("无法还原 FML 侧别委托", error);
            }
        }
    }

    /** 取回已装入的fakeClient实例。 */
    public Minecraft 客户端() {
        return fakeClient;
    }

    @Override
    public void close() {
        setStatic("instance", originalStaticInstance);
    }

    /** 执行全部待处理的客户端线程任务（顺序与受理一致）。 */
    public void 跑完客户端任务() {
        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            task.run();
        }
    }

    /** 未处理的任务数。 */
    public int 待处理任务数() {
        return pendingTasks.size();
    }

    private void 装入运行态() {
        set(fakeClient, "scheduledTasks", pendingTasks);
        setStatic("instance", fakeClient);
    }

    private static Minecraft 造客户端() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return (Minecraft)
                    unsafeClass
                            .getMethod("allocateInstance", Class.class)
                            .invoke(unsafe, Minecraft.class);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法构造测试用 Minecraft 替身", error);
        }
    }

    /** 造一个只实现接口、方法体返回默认值的代理（供无法用具体类的场景使用）。 */
    public static Object 代理(Class<?> type, java.lang.reflect.InvocationHandler handler) {
        return java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    /** 方法返回值默认值：引用类型 {@code null}、基本类型零值；{@code Object} 三方法合理实现。 */
    public static Object 默认值(Object source, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            String name = method.getName();
            if (name.equals("equals")) {
                // 替身未覆写 equals，语义即对象身份：用 identityHashCode 表达同一性，避免引用比较告警
                return System.identityHashCode(source) == System.identityHashCode(args[0]);
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(source);
            }
            if (name.equals("toString")) {
                return "Forge 测试替身";
            }
            return null;
        }
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = Minecraft.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法写入 Minecraft." + name, error);
        }
    }

    private static void setStatic(String name, Object value) {
        try {
            Field field = Minecraft.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法写入 Minecraft." + name, error);
        }
    }
}
