package top.wcpe.mc.mpmt.platform.sponge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.server.BanService;

/** Sponge RC1365 参数化机器码管理命令；状态变更全部委托 BanService。 */
final class SpongeMachineCodeCommand {

    static final String PERMISSION = "mpmt.machinecode.manage";
    private static final String DEFAULT_REASON = "管理员封禁";

    private final Supplier<BanService> banService;
    private final Supplier<SchedulerPort> scheduler;
    private final Parameter.Value<String> banCode = Parameter.string().key("code").build();
    private final Parameter.Value<String> unbanCode = Parameter.string().key("code").build();
    private final Parameter.Value<String> reason =
            Parameter.remainingJoinedStrings().key("reason").optional().build();

    private SpongeMachineCodeCommand(
            Supplier<BanService> banService, Supplier<SchedulerPort> scheduler) {
        this.banService = Objects.requireNonNull(banService, "banService 不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不能为空");
    }

    static Command.Parameterized create(
            Supplier<BanService> banService, Supplier<SchedulerPort> scheduler) {
        SpongeMachineCodeCommand commands =
                new SpongeMachineCodeCommand(banService, scheduler);
        Command.Parameterized machineCode = Command.builder()
                .permission(PERMISSION)
                .addChild(commands.banCommand(), "ban")
                .addChild(commands.unbanCommand(), "unban")
                .addChild(commands.listCommand(), "list")
                .build();
        return Command.builder()
                .permission(PERMISSION)
                .addChild(machineCode, "machinecode")
                .build();
    }

    private Command.Parameterized banCommand() {
        return Command.builder()
                .permission(PERMISSION)
                .addParameter(banCode)
                .addParameter(reason)
                .executor(this::ban)
                .build();
    }

    private Command.Parameterized unbanCommand() {
        return Command.builder()
                .permission(PERMISSION)
                .addParameter(unbanCode)
                .executor(this::unban)
                .build();
    }

    private Command.Parameterized listCommand() {
        return Command.builder()
                .permission(PERMISSION)
                .executor(this::list)
                .build();
    }

    private CommandResult ban(CommandContext context) {
        Responder responder = responder(context);
        MachineCode code = new MachineCode(context.requireOne(banCode));
        String detail = context.one(reason).orElse(DEFAULT_REASON);
        service()
                .ban(code, detail)
                .whenComplete((ignored, error) -> respond(responder, error, "已封禁机器码：" + code.getValue(), "封禁"));
        return CommandResult.success();
    }

    private CommandResult unban(CommandContext context) {
        Responder responder = responder(context);
        MachineCode code = new MachineCode(context.requireOne(unbanCode));
        service()
                .unban(code)
                .whenComplete((ignored, error) -> respond(responder, error, "已解封机器码：" + code.getValue(), "解封"));
        return CommandResult.success();
    }

    private CommandResult list(CommandContext context) {
        Responder responder = responder(context);
        BanService service = service();
        if (service.state() != BanService.State.READY) {
            responder.send("封禁列表读取失败：持久化或服务不可用，当前状态：" + service.state());
            return CommandResult.success();
        }
        List<BanEntry> entries = service.list();
        responder.send(entries.isEmpty() ? "当前没有机器码封禁" : format(entries));
        return CommandResult.success();
    }

    private BanService service() {
        BanService service = banService.get();
        if (service == null) {
            throw new IllegalStateException("封禁服务尚未装配");
        }
        return service;
    }

    private Responder responder(CommandContext context) {
        return new Responder(context, scheduler.get());
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

    /** 异步完成后切回同步调度器；玩家响应按 UUID 重查当前在线对象。 */
    private static final class Responder {
        private final CommandContext context;
        private final SchedulerPort scheduler;
        private final UUID playerId;

        private Responder(CommandContext context, SchedulerPort scheduler) {
            this.context = context;
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不能为空");
            this.playerId = context.cause()
                    .first(ServerPlayer.class)
                    .map(ServerPlayer::uniqueId)
                    .orElse(null);
        }

        private void send(String message) {
            Component component = Component.text(message);
            if (playerId == null) {
                scheduler.runGlobal(() -> context.sendMessage(component));
                return;
            }
            scheduler.runForEntity(
                    new EntityRef(playerId),
                    () -> Sponge.server().player(playerId).ifPresent(player -> player.sendMessage(component)));
        }
    }
}
