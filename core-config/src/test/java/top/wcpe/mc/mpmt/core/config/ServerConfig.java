package top.wcpe.mc.mpmt.core.config;

import java.util.List;

/**
 * 测试夹具：典型类型化配置模型（嵌套 + 列表 + 基本类型），YAML 与 JSON 共用。
 * 用公有字段 + 隐式无参构造，验证两种 loader 均按字段名映射。
 */
public class ServerConfig {

    public String name;
    public int port;
    public boolean enabled;
    public List<String> tags;
    public Nested nested;

    /** 嵌套配置段。 */
    public static class Nested {
        public String host;
        public int retry;
    }
}
