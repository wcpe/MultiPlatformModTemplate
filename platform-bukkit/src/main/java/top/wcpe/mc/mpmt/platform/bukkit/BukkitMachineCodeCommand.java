package top.wcpe.mc.mpmt.platform.bukkit;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.server.BanService;

/** Bukkit 原生机器码管理命令；仅解析与展示，状态变更全部委托 BanService。 */
final class BukkitMachineCodeCommand implements CommandExecutor {

    static final String PERMISSION = "mpmt.machinecode.manage";
    private static final String DEFAULT_REASON = "管理员封禁";
    private static final String USAGE =
            "用法：/mpmt machinecode ban <code> [reason...] | unban <code> | list";

    private final BanService banService;
    private final SchedulerPort scheduler;
    private final Server server;

    BukkitMachineCodeCommand(BanService banService, SchedulerPort scheduler, Server server) {
        this.banService = Objects.requireNonNull(banService, "banService 不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不能为空");
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Responder responder = new Responder(server, scheduler, sender);
        if (args.length < 2 || !"machinecode".equalsIgnoreCase(args[0])) {
            responder.send(USAGE);
            return true;
        }
        execute(args, responder);
        return true;
    }

    private void execute(String[] args, Responder responder) {
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "ban":
                ban(args, responder);
                break;
            case "unban":
                unban(args, responder);
                break;
            case "list":
                list(responder);
                break;
            default:
                responder.send(USAGE);
        }
    }

    private void ban(String[] args, Responder responder) {
        if (args.length < 3) {
            responder.send(USAGE);
            return;
        }
        MachineCode code = new MachineCode(args[2]);
        String reason = args.length > 3
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                : DEFAULT_REASON;
        banService
                .ban(code, reason)
                .whenComplete((ignored, error) -> respond(responder, error, "已封禁机器码：" + code.getValue(), "封禁"));
    }

    private void unban(String[] args, Responder responder) {
        if (args.length != 3) {
            responder.send(USAGE);
            return;
        }
        MachineCode code = new MachineCode(args[2]);
        banService
                .unban(code)
                .whenComplete((ignored, error) -> respond(responder, error, "已解封机器码：" + code.getValue(), "解封"));
    }

    private void list(Responder responder) {
        if (banService.state() != BanService.State.READY) {
            responder.send("封禁列表读取失败：持久化或服务不可用，当前状态：" + banService.state());
            return;
        }
        List<BanEntry> entries = banService.list();
        responder.send(entries.isEmpty() ? "当前没有机器码封禁" : format(entries));
    }

    private static String format(List<BanEntry> entries) {
        StringBuilder output = new StringBuilder("机器码封禁列表（").append(entries.size()).append("）：");
        for (BanEntry entry : entries) {
            output.append('\n')
                    .append(entry.getCode().getValue())
                    .append(" - ")
                    .append(entry.getReason());
        }
        return output.toString();
    }

    private static void respond(
            Responder responder, Throwable error, String success, String operation) {
        responder.send(error == null
                ? success
                : operation + "失败：持久化或服务不可用：" + messageOf(error));
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** 异步完成后按命令来源切回主线程或玩家实体归属，并重查当前在线玩家。 */
    private static final class Responder {
        private final Server server;
        private final SchedulerPort scheduler;
        private final CommandSender sender;
        private final UUID playerId;

        private Responder(Server server, SchedulerPort scheduler, CommandSender sender) {
            this.server = server;
            this.scheduler = scheduler;
            this.sender = sender;
            this.playerId = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        }

        private void send(String message) {
            if (playerId == null) {
                scheduler.runGlobal(() -> sender.sendMessage(message));
                return;
            }
            scheduler.runForEntity(
                    new EntityRef(playerId),
                    () -> {
                        Player current = server.getPlayer(playerId);
                        if (current != null && current.isOnline()) {
                            current.sendMessage(message);
                        }
                    });
        }
    }
}
