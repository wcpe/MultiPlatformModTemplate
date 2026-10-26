package top.wcpe.mc.mpmt.examples.counter;

/**
 * 客户端 HUD 渲染接口（示范域 counter 的内部抽象）。
 *
 * <p>放在 {@code client-internal}：只声明"怎么渲染"，不含任何渲染实现（ADR-0030 决策 5）。
 * 实现放 {@code client}；域内其它模块只依赖本接口，不依赖具体实现。
 */
public interface CounterHudRenderer {

    /**
     * 渲染一次加入次数提示。
     *
     * @param payload 渲染载荷（内部 DTO）
     */
    void render(CounterHudPayload payload);
}
