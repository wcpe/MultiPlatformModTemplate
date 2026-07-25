package top.wcpe.mc.mpmt.platform.bukkit.acceptance.scenario;

import java.lang.reflect.Method;
import java.util.Map;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.platform.bukkit.acceptance.BukkitServerGameTestContext;

/** R5：从 CatServer 的 Forge Loader 权威断言服务端未加载我方 Forge 产品或验收 mod。 */
public final class ServerForgeProductAbsentServerScenario extends R5ServerScenario {

    private static final String FORGE_LOADER_CLASS = "net.minecraftforge.fml.common.Loader";
    private static final String PRODUCT_MOD_ID = "mpmt";
    private static final String ACCEPTANCE_MOD_ID = "mpmt_acceptance";

    @Override
    public String id() {
        return "server-forge-product-absent";
    }

    @Override
    protected void runR5(ServerGameTestContext context) {
        BukkitServerGameTestContext bukkit = bukkit(context);
        Map<?, ?> indexedMods =
                context.onMain(
                        () -> indexedMods(bukkit.server().getClass().getClassLoader()));
        context.assertTrue(
                !indexedMods.containsKey(PRODUCT_MOD_ID),
                "CatServer 服务端 Forge 模组列表包含我方产品 mpmt");
        context.assertTrue(
                !indexedMods.containsKey(ACCEPTANCE_MOD_ID),
                "CatServer 服务端 Forge 模组列表包含我方验收模组 mpmt_acceptance");
    }

    private static Map<?, ?> indexedMods(ClassLoader classLoader) {
        try {
            Class<?> loaderClass = Class.forName(FORGE_LOADER_CLASS, false, classLoader);
            Method instanceMethod = loaderClass.getMethod("instance");
            Object loader = instanceMethod.invoke(null);
            Method indexedMethod = loaderClass.getMethod("getIndexedModList");
            Object indexed = indexedMethod.invoke(loader);
            if (!(indexed instanceof Map)) {
                throw new IllegalStateException("Forge Loader 返回的模组索引不是 Map");
            }
            return (Map<?, ?>) indexed;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 CatServer Forge 模组列表失败", e);
        }
    }
}
