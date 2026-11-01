package top.wcpe.mc.mpmt.platform.forge.capability;

import java.io.File;
import java.util.Objects;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import top.wcpe.mc.mpmt.config.DirectoryConfigPort;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;
import top.wcpe.mc.mpmt.kernel.port.DataDirectoryPort;
import top.wcpe.mc.mpmt.kernel.port.MessagePort;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;
import top.wcpe.mc.mpmt.kernel.port.PlayerPort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.port.WorldPort;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * Forge 1.12.2 平台能力装配（L3，FR-26）：在服务端启动后注册共享端口，
 * 经 {@code Feature} 自动发现挂载 L0 功能域（ADR-0028），并把玩家进 / 退事件桥接为领域事件（ADR-0011）。
 *
 * <p><b>与其它车道的差别（刻意的）</b>：本车道没有服务端传输层（产品是客户端握手），
 * 因此不注册 {@code ConnectionControlPort}——它需要一个由服务端网络层产生的 {@code ConnectionHandle}，
 * 而这条车道目前没有任何东西会产生该句柄。与其注册一个「永远拿不到句柄」的端口，
 * 不如让它缺席：需要它的域会在此车道明确报缺失，而不是在运行期踩类型错误。
 *
 * <p>域读配置经 L0 {@link ConfigPort}（实现为 {@link DirectoryConfigPort}，文件落在
 * {@code <配置目录>/mpmt/config/}），域侧只依赖 {@code :core:kernel}（ADR-0030 决策 5）。
 */
public final class Forge112CapabilityBootstrap {

    private Forge112CapabilityBootstrap() {
        // 工具类不实例化
    }

    /**
     * 装配服务端能力端口与领域事件桥接。
     *
     * @param server          当前服务端实例，不可为空
     * @param configDirectory FML 提供的配置目录，不可为空
     * @param runtime         待注册端口的运行时，不可为空
     */
    public static void register(MinecraftServer server, File configDirectory, MpmtRuntime runtime) {
        Objects.requireNonNull(server, "server 不能为空");
        Objects.requireNonNull(configDirectory, "配置目录不能为空");
        Objects.requireNonNull(runtime, "runtime 不能为空");

        DataDirectoryPort dataDirectory = new Forge112DataDirectoryPort(configDirectory);
        PersistencePort persistence = new Forge112PersistencePort(dataDirectory);
        ConfigPort config = new DirectoryConfigPort(dataDirectory);
        MessagePort message = new Forge112MessagePort(server);
        PlayerPort players = new Forge112PlayerPort(server);
        WorldPort worlds = new Forge112WorldPort(server);
        SchedulerPort scheduler = new Forge112SchedulerPort(server);

        runtime.ports().register(DataDirectoryPort.class, dataDirectory);
        runtime.ports().register(PersistencePort.class, persistence);
        runtime.ports().register(ConfigPort.class, config);
        runtime.ports().register(MessagePort.class, message);
        runtime.ports().register(PlayerPort.class, players);
        runtime.ports().register(WorldPort.class, worlds);
        runtime.ports().register(SchedulerPort.class, scheduler);

        EventBusPort eventBus = runtime.eventBus();
        // 功能域经 META-INF/services 自动发现装配（ADR-0028）：车道不认识任何具体域，
        // 新增域不需要改这里。discover 只是登记，真正的 onEnable（取端口、订阅）发生在
        // runtime.enable()，故此刻端口已全部注册完毕。
        runtime.features().discover(Forge112CapabilityBootstrap.class.getClassLoader());
        MinecraftForge.EVENT_BUS.register(new PlayerConnectionBridge(eventBus));
    }

    /** 1.12.2 玩家登录与登出事件桥接：转成领域事件投递到自有 EventBus。 */
    private static final class PlayerConnectionBridge {

        private final EventBusPort eventBus;

        PlayerConnectionBridge(EventBusPort eventBus) {
            this.eventBus = eventBus;
        }

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            eventBus.publish(new PlayerJoinedEvent(toRef(event.player)));
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            eventBus.publish(new PlayerLeftEvent(toRef(event.player)));
        }

        private static PlayerRef toRef(EntityPlayer player) {
            return new PlayerRef(player.getUniqueID(), player.getName());
        }
    }
}
