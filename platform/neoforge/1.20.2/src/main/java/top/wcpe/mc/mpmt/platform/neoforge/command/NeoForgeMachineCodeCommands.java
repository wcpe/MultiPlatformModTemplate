package top.wcpe.mc.mpmt.platform.neoforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.server.BanService;

/** NeoForge 原生 Brigadier 机器码封禁命令。 */
public final class NeoForgeMachineCodeCommands {

    private static final String DEFAULT_REASON = "由管理员封禁";

    private NeoForgeMachineCodeCommands() {
        // 工具类不实例化
    }

    /** 注册 /mpmt machinecode ban、unban 与 list。 */
    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Supplier<BanService> serviceSupplier) {
        Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        Objects.requireNonNull(serviceSupplier, "serviceSupplier 不能为空");
        dispatcher.register(
                Commands.literal("mpmt")
                        .requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("machinecode")
                                .then(banCommand(serviceSupplier))
                                .then(unbanCommand(serviceSupplier))
                                .then(Commands.literal("list")
                                        .executes(context -> list(context, serviceSupplier)))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> banCommand(
            Supplier<BanService> serviceSupplier) {
        return Commands.literal("ban")
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(context -> ban(context, serviceSupplier, DEFAULT_REASON))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> ban(
                                        context,
                                        serviceSupplier,
                                        StringArgumentType.getString(context, "reason")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> unbanCommand(
            Supplier<BanService> serviceSupplier) {
        return Commands.literal("unban")
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(context -> unban(context, serviceSupplier)));
    }

    private static int ban(
            CommandContext<CommandSourceStack> context,
            Supplier<BanService> serviceSupplier,
            String reason) {
        BanService service = readyService(context.getSource(), serviceSupplier);
        if (service == null) {
            return 0;
        }
        String value = StringArgumentType.getString(context, "code");
        service.ban(new MachineCode(value), reason)
                .whenComplete((ignored, error) -> complete(context.getSource(), "已封禁机器码 " + value, error));
        return Command.SINGLE_SUCCESS;
    }

    private static int unban(
            CommandContext<CommandSourceStack> context,
            Supplier<BanService> serviceSupplier) {
        BanService service = readyService(context.getSource(), serviceSupplier);
        if (service == null) {
            return 0;
        }
        String value = StringArgumentType.getString(context, "code");
        service.unban(new MachineCode(value))
                .whenComplete((ignored, error) -> complete(context.getSource(), "已解封机器码 " + value, error));
        return Command.SINGLE_SUCCESS;
    }

    private static int list(
            CommandContext<CommandSourceStack> context,
            Supplier<BanService> serviceSupplier) {
        BanService service = readyService(context.getSource(), serviceSupplier);
        if (service == null) {
            return 0;
        }
        List<BanEntry> entries = service.list();
        if (entries.isEmpty()) {
            success(context.getSource(), "当前没有机器码封禁");
            return Command.SINGLE_SUCCESS;
        }
        success(context.getSource(), "机器码封禁共 " + entries.size() + " 条：");
        for (BanEntry entry : entries) {
            success(context.getSource(), entry.getCode().getValue() + " - " + entry.getReason());
        }
        return entries.size();
    }

    private static BanService readyService(
            CommandSourceStack source, Supplier<BanService> serviceSupplier) {
        BanService service = serviceSupplier.get();
        if (service == null || service.state() != BanService.State.READY) {
            source.sendFailure(Component.literal("封禁服务尚未就绪"));
            return null;
        }
        return service;
    }

    private static void complete(CommandSourceStack source, String message, Throwable error) {
        source.getServer().execute(() -> {
            if (error == null) {
                success(source, message);
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            source.sendFailure(Component.literal("封禁操作失败：" + cause.getMessage()));
        });
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
