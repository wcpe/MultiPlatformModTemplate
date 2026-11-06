package top.wcpe.mc.mpmt.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;

/**
 * 启动期缺省配置装配：把类路径里声明的全部缺省配置文件落到平台配置目录。
 *
 * <h2>它解决什么</h2>
 * 每个域自带配置（如 {@code counter.yml}），但用户拿到的是一个空目录，想知道默认值得翻源码。
 * 本类在启动时扫一遍所有 jar 里的<b>清单</b>，把带中文注释的缺省文件拷到用户面前，
 * 之后就变成“用户改文件”而不是“改代码重编”。
 *
 * <h2>为什么用清单而不是扫目录</h2>
 * 类路径上不能枚举某个前缀下的全部资源（只能按确名查）。
 * 所以约定：每个自带配置的域在自己的 jar 里放一份
 * {@code META-INF/mpmt-config-defaults.list}，每行一个配置文件名（{@code #} 开头为注释）。
 * 本类把所有清单合并后逐个写入。
 *
 * <h2>幂等与失败隔离</h2>
 * <ul>
 *   <li>文件已存在则<b>一字不改</b>（见 {@link ConfigPort#writeDefault}）；
 *   <li>单个清单声明的文件写失败不影响其它——缺一块配置不该导致服务起不来，但会记一条告警。
 * </ul>
 */
public final class ConfigDefaults {

    /** 缺省配置清单的类路径位置（每个域模块各一份）。 */
    public static final String MANIFEST_RESOURCE = "META-INF/mpmt-config-defaults.list";

    private static final Logger LOGGER = Logger.getLogger("mpmt");

    private ConfigDefaults() {
        // 静态装配入口，不实例化
    }

    /**
     * 把类路径里声明的全部缺省配置落到 {@code configPort} 对应的目录。
     *
     * @param configPort 配置端口（平台装配期已注册）
     * @param classLoader 承载域模块的类加载器
     * @return 本次实际新建的文件名（已存在的不在内）
     */
    public static List<String> writeAll(ConfigPort configPort, ClassLoader classLoader) {
        Objects.requireNonNull(configPort, "配置端口不能为空");
        Objects.requireNonNull(classLoader, "类加载器不能为空");

        List<String> written = new ArrayList<>();

        for (String fileName : declaredFileNames(classLoader)) {
            try {
                if (configPort.writeDefault(fileName)) {
                    written.add(fileName);
                }
            } catch (RuntimeException e) {
                // 单份缺省配置写失败不阻断启动：它只是便利功能，不是运行必需。
                LOGGER.log(Level.WARNING, "写缺省配置失败，已跳过：" + fileName, e);
            }
        }

        if (!written.isEmpty()) {
            LOGGER.info("已生成缺省配置：" + written);
        }
        return Collections.unmodifiableList(written);
    }

    /**
     * 读出类路径里全部清单声明的配置文件名（去重、保持发现顺序）。
     *
     * @param classLoader 承载域模块的类加载器
     * @return 配置文件名列表
     */
    public static List<String> declaredFileNames(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "类加载器不能为空");
        List<String> names = new ArrayList<>();

        try {
            java.util.Enumeration<java.net.URL> resources = classLoader.getResources(MANIFEST_RESOURCE);
            while (resources.hasMoreElements()) {
                try (InputStream in = resources.nextElement().openStream()) {
                    readManifest(in, names);
                } catch (IOException e) {
                    // 单个清单读失败不影响其它域
                    LOGGER.log(Level.WARNING, "读缺省配置清单失败，已跳过", e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "枚举缺省配置清单失败", e);
        }

        return Collections.unmodifiableList(names);
    }

    /** 解析一份清单：每行一个文件名，{@code #} 开头为注释，空行忽略；重名只取首次。 */
    private static void readManifest(InputStream in, List<String> into) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                if (!into.contains(name)) {
                    into.add(name);
                }
            }
        }
    }
}
