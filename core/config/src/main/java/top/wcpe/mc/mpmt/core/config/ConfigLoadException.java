package top.wcpe.mc.mpmt.core.config;

/**
 * 配置加载失败的业务异常：文件缺失 / IO 错误 / 解析失败 / 格式不可判别等，
 * 统一以本异常对外暴露，避免裸抛底层库异常或吞异常（反模式禁令 §7）。
 */
public class ConfigLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigLoadException(String message) {
        super(message);
    }

    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
