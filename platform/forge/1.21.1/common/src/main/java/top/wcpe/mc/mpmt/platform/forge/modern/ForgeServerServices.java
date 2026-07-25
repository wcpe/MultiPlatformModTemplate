package top.wcpe.mc.mpmt.platform.forge.modern;

import java.util.Objects;
import java.util.UUID;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.BanService;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;

/** Forge 1.21.1 服务端闭环持有者：统一复用唯一的封禁表、会话表、网络特性与封禁服务。 */
public final class ForgeServerServices {

    private final SessionRegistry sessionRegistry;
    private final ServerNetworkFeature networkFeature;
    private final BanService banService;

    private ForgeServerServices(
            SessionRegistry sessionRegistry,
            ServerNetworkFeature networkFeature,
            BanService banService) {
        this.sessionRegistry = sessionRegistry;
        this.networkFeature = networkFeature;
        this.banService = banService;
    }

    /** 从运行时端口装配并初始化服务端闭环。 */
    public static ForgeServerServices install(MpmtRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime 不能为空");
        BanRegistry bans = new BanRegistry();
        SessionRegistry sessions = new SessionRegistry();
        BanService service = new BanService(
                bans,
                sessions,
                runtime.ports().get(PersistencePort.class),
                runtime.ports().get(SchedulerPort.class),
                runtime.ports().get(ConnectionControlPort.class));
        ServerNetworkFeature network =
                new ServerNetworkFeature(
                        bans, () -> UUID.randomUUID().toString(), sessions, service::state);
        runtime.features().register(network);
        service.initialize();
        return new ForgeServerServices(sessions, network, service);
    }

    public ServerNetworkFeature networkFeature() {
        return networkFeature;
    }

    public BanService banService() {
        return banService;
    }
}
