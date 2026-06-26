package top.wcpe.mc.mpmt.platform.forge.acceptance.client;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/** 客户端验证器注册表（ADR-0014）：经 {@code ServiceLoader} 发现全部验证器，按 scenarioId 索引、重复即拒。 */
public final class ClientVerifierRegistry {

    private final Map<String, ClientVerifier> byId = new HashMap<>();

    public ClientVerifierRegistry() {
        for (ClientVerifier verifier :
                ServiceLoader.load(ClientVerifier.class, getClass().getClassLoader())) {
            if (byId.put(verifier.scenarioId(), verifier) != null) {
                throw new IllegalStateException("客户端验证器 scenarioId 重复：" + verifier.scenarioId());
            }
        }
    }

    /** 按 scenarioId 查找验证器（无则 null）。 */
    public ClientVerifier find(String scenarioId) {
        return byId.get(scenarioId);
    }
}
