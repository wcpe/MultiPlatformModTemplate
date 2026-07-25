package top.wcpe.mc.mpmt.core.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Supplier;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;

/**
 * 默认弱客户端标识提供者：取若干弱系统属性拼接后求 SHA-256（hex）。
 *
 * <p><b>弱标识</b>：同机可能变、异机可能撞，可被反编译伪造、可缺席——仅作威慑性标签、非安全凭据（见 SECURITY.md）。
 * 原始来源经构造注入，便于穷举测试（默认取系统属性）。
 */
public final class DefaultMachineCodeProvider implements MachineCodeProvider {

    private final Supplier<String> rawSource;

    public DefaultMachineCodeProvider() {
        this(DefaultMachineCodeProvider::gatherWeakAttributes);
    }

    public DefaultMachineCodeProvider(Supplier<String> rawSource) {
        this.rawSource = Objects.requireNonNull(rawSource, "rawSource 不能为空");
    }

    @Override
    public String get() {
        return sha256Hex(rawSource.get());
    }

    /** 拼接可得的弱系统属性（不含敏感信息）。 */
    private static String gatherWeakAttributes() {
        return String.join("|",
                systemProperty("os.name"),
                systemProperty("os.arch"),
                systemProperty("user.name"),
                systemProperty("user.home"));
    }

    private static String systemProperty(String key) {
        return String.valueOf(System.getProperty(key, ""));
    }

    /** SHA-256 → 小写十六进制。 */
    static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 必备算法，理论不会发生
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
