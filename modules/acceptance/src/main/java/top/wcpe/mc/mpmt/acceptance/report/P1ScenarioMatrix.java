package top.wcpe.mc.mpmt.acceptance.report;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** P1 必需场景与平台适用矩阵；清单顺序同时作为权威报告中的稳定场景顺序。 */
public final class P1ScenarioMatrix {

    public static final String HANDSHAKE_SUCCESS = "acceptance/handshake-success";
    public static final String HANDSHAKE_INCOMPATIBLE = "acceptance/handshake-incompatible";
    public static final String MACHINE_CODE_SESSION = "acceptance/machine-code-session";
    public static final String BAN_RECONNECT = "acceptance/ban-reconnect";
    public static final String UNBAN_RECONNECT = "acceptance/unban-reconnect";
    public static final String FRAGMENT_CRC = "acceptance/fragment-crc";
    public static final String FRAGMENT_TIMEOUT_RETRY_RESYNC = "acceptance/fragment-timeout-retry-resync";
    public static final String SESSION_HEARTBEAT_RTT_TIMEOUT = "acceptance/session-heartbeat-rtt-timeout";
    public static final String CAPABILITY_EVENT_BUS = "acceptance/capability-eventbus";
    public static final String HUD_TITLE = "acceptance/hud-title";
    public static final String HUD_ACTIONBAR = "acceptance/hud-actionbar";
    public static final String HUD_TOAST = "acceptance/hud-toast";
    public static final String HUD_CHAT = "acceptance/hud-chat";
    public static final String INTEGRATED_LOOPBACK = "acceptance/integrated-loopback";
    public static final String REAL_ROUND_TRIP = "acceptance/real-round-trip";

    private static final List<String> SIM_REQUIRED = immutableList(
            HANDSHAKE_SUCCESS,
            HANDSHAKE_INCOMPATIBLE,
            MACHINE_CODE_SESSION,
            BAN_RECONNECT,
            UNBAN_RECONNECT,
            FRAGMENT_CRC,
            FRAGMENT_TIMEOUT_RETRY_RESYNC,
            SESSION_HEARTBEAT_RTT_TIMEOUT,
            CAPABILITY_EVENT_BUS,
            HUD_TITLE,
            HUD_ACTIONBAR,
            HUD_TOAST,
            HUD_CHAT,
            INTEGRATED_LOOPBACK);

    private static final List<String> REAL_REQUIRED = immutableList(
            HANDSHAKE_SUCCESS,
            HANDSHAKE_INCOMPATIBLE,
            MACHINE_CODE_SESSION,
            BAN_RECONNECT,
            UNBAN_RECONNECT,
            FRAGMENT_CRC,
            FRAGMENT_TIMEOUT_RETRY_RESYNC,
            SESSION_HEARTBEAT_RTT_TIMEOUT,
            CAPABILITY_EVENT_BUS,
            HUD_TITLE,
            HUD_ACTIONBAR,
            HUD_TOAST,
            HUD_CHAT,
            REAL_ROUND_TRIP);

    private static final Map<String, List<String>> REQUIRED_BY_PLATFORM = createMatrix();

    private P1ScenarioMatrix() {
        // 工具类不实例化
    }

    /** 返回平台或验收矩阵车道的 P1 必需清单；未知值立即拒绝，避免静默漏验。 */
    public static List<String> requiredFor(String platform) {
        if (platform == null) {
            throw new NullPointerException("platform 不能为空");
        }
        List<String> required = REQUIRED_BY_PLATFORM.get(platform.toLowerCase(Locale.ROOT));
        if (required == null) {
            throw new IllegalArgumentException("未知验收平台或矩阵：" + platform);
        }
        return required;
    }

    /** 返回全部已登记平台矩阵。 */
    public static Map<String, List<String>> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(REQUIRED_BY_PLATFORM));
    }

    private static Map<String, List<String>> createMatrix() {
        Map<String, List<String>> matrix = new LinkedHashMap<>();
        register(matrix, SIM_REQUIRED, "sim-fabric", "sim-forge", "sim-neoforge");
        register(
                matrix,
                REAL_REQUIRED,
                "fabric",
                "forge",
                "neoforge",
                "bukkit",
                "paper",
                "folia",
                "sponge",
                "fabric-fabric",
                "forge-forge",
                "neoforge-neoforge",
                "paper-fabric",
                "paper-forge",
                "folia-fabric",
                "sponge-fabric",
                "fabric-integrated");
        return Collections.unmodifiableMap(matrix);
    }

    private static void register(Map<String, List<String>> matrix, List<String> scenarios, String... platforms) {
        for (String platform : platforms) {
            matrix.put(platform, scenarios);
        }
    }

    private static List<String> immutableList(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
