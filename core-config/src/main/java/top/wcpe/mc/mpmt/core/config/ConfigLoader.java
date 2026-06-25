package top.wcpe.mc.mpmt.core.config;

import java.io.Reader;

/** 格式无关的配置加载契约：把字符流读为给定的类型化模型。各格式由实现策略各司其一。 */
public interface ConfigLoader {

    /**
     * 把 {@code reader} 内容加载为 {@code type} 类型实例。
     *
     * @throws ConfigLoadException 内容非法 / 解析失败 / 内容为空
     */
    <T> T load(Reader reader, Class<T> type);
}
