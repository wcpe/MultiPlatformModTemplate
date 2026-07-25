package top.wcpe.mc.mpmt.core.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.Reader;
import java.util.Objects;

/** JSON 加载器（gson）：按字段名映射为目标类型。 */
public final class JsonConfigLoader implements ConfigLoader {

    private final Gson gson = new Gson();

    @Override
    public <T> T load(Reader reader, Class<T> type) {
        Objects.requireNonNull(reader, "reader 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        try {
            T value = gson.fromJson(reader, type);
            if (value == null) {
                throw new ConfigLoadException("JSON 内容为空，无法加载为 " + type.getName());
            }
            return value;
        } catch (ConfigLoadException e) {
            throw e;
        } catch (JsonParseException e) {
            // gson 的语法 / IO 解析异常均为 JsonParseException 子类，统一包成业务异常
            throw new ConfigLoadException("JSON 加载失败：" + e.getMessage(), e);
        }
    }
}
