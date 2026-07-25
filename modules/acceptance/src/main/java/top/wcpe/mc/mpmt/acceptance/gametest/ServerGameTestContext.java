package top.wcpe.mc.mpmt.acceptance.gametest;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 服务端 GameTest 上下文（ADR-0014）：给场景在驱动线程提供"切主线程执行 / 等 tick / 轮询 / 断言"能力。
 *
 * <p>线程切换与世界访问（{@link #onMain} / {@link #waitTicks} / {@link #awaitUntil}）由平台实现；
 * 断言与跳过是平台无关默认方法、抛对应异常由 {@link ServerGameTestRunner} 归类为 FAIL / SKIP。
 */
public interface ServerGameTestContext {

    /** 在服务端主线程同步执行并返回结果（驱动线程阻塞等待）。不得在主线程调用（自死锁）。 */
    <T> T onMain(Supplier<T> block);

    /** 在服务端主线程同步执行。 */
    void onMain(Runnable block);

    /** 驱动线程等待若干 tick。 */
    void waitTicks(int ticks);

    /** 轮询等待条件成立（主线程求值）；返回是否在超时前成立。 */
    boolean awaitUntil(int timeoutTicks, BooleanSupplier predicate);

    /** 断言失败：抛 {@link ServerGameTestAssertionError}（计 FAIL）。 */
    default void fail(String message) {
        throw new ServerGameTestAssertionError(message);
    }

    /** 跳过：抛 {@link ServerGameTestSkipException}（计 SKIP，不计失败）。 */
    default void skip(String message) {
        throw new ServerGameTestSkipException(message);
    }

    /** 断言条件为真，否则 FAIL。 */
    default void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    /** 断言相等，否则 FAIL（含期望 / 实际诊断）。 */
    default <T> void assertEquals(T expected, T actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail(message + "（期望=" + expected + " 实际=" + actual + "）");
        }
    }
}
