package top.wcpe.mc.mpmt.protocol;

/**
 * 协议层业务异常：非法 / 不兼容 / 截断的字节流在解析时抛出。
 *
 * <p>用于"明确拒绝并记录、不静默错乱、不崩溃"（API.md §2 错误约定）。调用方应捕获并转换为
 * 对外可理解的结果（如握手拒绝），而非任由其作为未受控崩溃传播。
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
