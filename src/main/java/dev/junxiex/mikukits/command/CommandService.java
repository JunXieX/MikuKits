package dev.junxiex.mikukits.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.junxiex.mikukits.MikuKitsPlugin;
import dev.junxiex.mikukits.config.ConfigManager;
import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.util.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * /mikukits 命令（Paper Brigadier）。在 onEnable 通过 LifecycleEvents.COMMANDS 注册。
 * <p>
 * 根命令与 /open 均直接打开菜单；/give、/reload、/info 需 mikukits.admin。
 */
public final class CommandService {

    private final MikuKitsPlugin plugin;

    public CommandService(MikuKitsPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            registrar.register(Commands.literal("mikukits")
                    .executes(this::openSelf)
                    .then(Commands.literal("open")
                            .executes(this::openSelf)
                            .then(Commands.argument("player", ArgumentTypes.player())
                                    .requires(this::isAdmin)
                                    .executes(this::openOther)))
                    .then(Commands.literal("give")
                            .requires(this::isAdmin)
                            .then(Commands.argument("player", ArgumentTypes.player())
                                    .then(Commands.argument("kit", StringArgumentType.string())
                                            .suggests((ctx, builder) -> {
                                                String remaining = builder.getRemainingLowerCase();
                                                for (Kit k : plugin.kitManager().all()) {
                                                    if (k.id().startsWith(remaining)) {
                                                        builder.suggest(
                                                                StringArgumentType.escapeIfRequired(k.id()));
                                                    }
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(this::give))))
                    .then(Commands.literal("reload")
                            .requires(this::isAdmin)
                            .executes(this::reload))
                    .then(Commands.literal("info")
                            .requires(this::isAdmin)
                            .executes(this::info))
                    .build(),
                    "mikukits", List.of("kit", "kits", "mk"));
        });
    }

    private boolean isAdmin(CommandSourceStack source) {
        return source.getSender().hasPermission(Permissions.ADMIN);
    }

    private int openSelf(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            plugin.menuService().openMain(player, 0);
        } else {
            ctx.getSource().getSender().sendMessage(plugin.configManager().message("command.console-no-menu"));
        }
        return 1;
    }

    private int openOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolvePlayer(ctx, "player");
        if (target == null) {
            sender.sendMessage(plugin.configManager().message("command.player-not-found"));
            return 0;
        }
        plugin.menuService().openMain(target, 0);
        return 1;
    }

    private int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        String kitId = StringArgumentType.getString(ctx, "kit");
        Kit kit = plugin.kitManager().get(kitId);
        if (kit == null) {
            sender.sendMessage(plugin.configManager().message("command.not-found", Map.of("kit", kitId)));
            return 0;
        }
        Player target = resolvePlayer(ctx, "player");
        if (target == null) {
            sender.sendMessage(plugin.configManager().message("command.player-not-found"));
            return 0;
        }
        // 发放必须在目标玩家线程；结果反馈需回到执行者自己的线程：
        // Folia 下执行者可能是另一区域的玩家，跨线程访问其实体属非法操作
        boolean scheduled = target.getScheduler().run(plugin,
                t -> plugin.dispatcher().forceGive(target, kit, msg -> feedback(sender, msg)), null) != null;
        if (!scheduled) {
            // 目标恰在调度前被移除/离线：给出明确反馈，避免管理员以为发放成功
            feedback(sender, plugin.configManager().message("command.give-offline",
                    Map.of("player", target.getName(), "kit", kit.id())));
        }
        return 1;
    }

    /** 把反馈投递到执行者自身线程；控制台无实体归属，可直接发送。 */
    private void feedback(CommandSender sender, Component message) {
        if (sender instanceof Player p) {
            // 执行者可能是另一区域线程上的玩家，发送消息必须回到其自身线程
            p.getScheduler().run(plugin, t -> p.sendMessage(message), null);
        } else {
            sender.sendMessage(message);
        }
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        // 重载在全局区域线程完成，反馈须延后到配置真正生效之后、且投递回执行者自身线程
        CommandSender sender = ctx.getSource().getSender();
        plugin.reloadConfigs(ok -> feedback(sender, ok
                ? plugin.configManager().message("command.reload",
                        Map.of("count", String.valueOf(plugin.kitManager().size())))
                : plugin.configManager().message("command.reload-failed")));
        return 1;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(plugin.configManager().message("command.info-header",
                Map.of("version", plugin.getPluginMeta().getVersion())));
        sender.sendMessage(plugin.configManager().message("command.info-body",
                Map.of("kits", String.valueOf(plugin.kitManager().size()),
                        "cached", String.valueOf(plugin.dataStore().cachedCount()),
                        "pending", String.valueOf(plugin.dataStore().pendingCount()),
                        "vault", ConfigManager.plain(plugin.configManager().message(
                                plugin.vaultHook().available() ? "command.vault-on" : "command.vault-off")))));
        return 1;
    }

    private Player resolvePlayer(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument(name, PlayerSelectorArgumentResolver.class);
        var list = resolver.resolve(ctx.getSource());
        return list.isEmpty() ? null : list.get(0);
    }
}
