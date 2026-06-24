package top.wcpe.mc.mpmt.acceptance.gametest;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 测试用假上下文：主线程操作同步执行、不等 tick、轮询即时求值；断言走接口默认方法。 */
final class FakeServerGameTestContext implements ServerGameTestContext {

    @Override
    public <T> T onMain(Supplier<T> block) {
        return block.get();
    }

    @Override
    public void onMain(Runnable block) {
        block.run();
    }

    @Override
    public void waitTicks(int ticks) {
        // 测试不需要真实等待
    }

    @Override
    public boolean awaitUntil(int timeoutTicks, BooleanSupplier predicate) {
        return predicate.getAsBoolean();
    }
}
