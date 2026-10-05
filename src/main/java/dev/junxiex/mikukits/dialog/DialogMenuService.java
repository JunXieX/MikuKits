package dev.junxiex.mikukits.dialog;

import dev.junxiex.mikukits.MikuKitsPlugin;
import dev.junxiex.mikukits.config.ConfigManager;
import dev.junxiex.mikukits.data.KitRecord;
import dev.junxiex.mikukits.data.LimitResult;
import dev.junxiex.mikukits.data.PlayerKitData;
import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.kit.PeriodType;
import dev.junxiex.mikukits.kit.reward.Reward;
import dev.junxiex.mikukits.util.Permissions;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Paper Dialog API 的礼包菜单。
 * <p>
 * 采用无状态设计：页码/礼包 id 编码进按钮 payload，交互时重新渲染，
 * 无需服务端维护"打开中菜单"集合，天然支持刷新且零常驻内存。
 * 倒计时不做每秒自动重发（Dialog 重发会闪烁），而是在每次打开/交互时渲染最新状态。
 * 所有玩家可见文本均来自 messages.yml。
 * <p>
 * 主菜单只列出当前"可领取"的礼包，故按钮上不再重复显示状态标记。
 */
public final class DialogMenuService {

    public static final Key CLICK_KEY = Key.key("mikukits", "menu_click");

    private final MikuKitsPlugin plugin;

    public DialogMenuService(MikuKitsPlugin plugin) {
        this.plugin = plugin;
    }

    /** 打开主菜单（第 page 页）。可从任意线程调用，内部投递到玩家实体线程。 */
    public void openMain(Player player, int page) {
        player.getScheduler().execute(plugin, () -> doOpenMain(player, page), null, 0L);
    }

    private void doOpenMain(Player player, int page) {
        PlayerKitData data = plugin.dataStore().get(player.getUniqueId());
        if (data == null || !data.loaded()) {
            player.sendMessage(plugin.configManager().message("menu.loading"));
            return;
        }
        ConfigManager cfg = plugin.configManager();
        boolean bypassCd = Permissions.canBypassCooldown(player);
        boolean bypassLimit = Permissions.canBypassLimit(player);
        // 本次渲染 now 恒定，周期 key 预计算一次供筛选与 tooltip 复用
        Map<PeriodType, String> periodKeys =
                plugin.limitService().currentPeriodKeys(System.currentTimeMillis());
        // 隐藏对该玩家当前不可领取的礼包（已领完 / 冷却中 / 本期已用完）
        List<Kit> kits = new ArrayList<>();
        for (Kit k : plugin.kitManager().visibleTo(player)) {
            if (plugin.limitService().checkLimits(k, data, bypassCd, bypassLimit, periodKeys)
                    == LimitResult.OK) {
                kits.add(k);
            }
        }
        int pageSize = cfg.menuPageSize();
        int totalPages = Math.max(1, (kits.size() + pageSize - 1) / pageSize);
        if (page < 0) {
            page = 0;
        }
        if (page >= totalPages) {
            page = totalPages - 1;
        }
        int from = page * pageSize;
        int to = Math.min(kits.size(), from + pageSize);

        List<ActionButton> buttons = new ArrayList<>();
        for (int i = from; i < to; i++) {
            buttons.add(kitButton(data, kits.get(i), page, periodKeys));
        }
        if (totalPages > 1) {
            if (page > 0) {
                buttons.add(navButton(cfg.message("menu.prev-page"), ClickPayload.PREV, page - 1));
            }
            if (page < totalPages - 1) {
                buttons.add(navButton(cfg.message("menu.next-page"), ClickPayload.NEXT, page + 1));
            }
        }
        if (buttons.isEmpty()) {
            buttons.add(ActionButton.builder(cfg.message("menu.empty"))
                    .action(DialogAction.customClick(CLICK_KEY, payload(ClickPayload.BACK, "", 0)))
                    .build());
        }

        Component info = cfg.message("menu.page-info", Map.of(
                "page", String.valueOf(page + 1),
                "total", String.valueOf(totalPages),
                "count", String.valueOf(kits.size())));

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.message("menu.main-title"))
                        .body(List.of(DialogBody.plainMessage(info)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    /** 打开礼包详情/确认页。page 为来源主菜单页码，用于领取后/返回时回到原页。可从任意线程调用。 */
    public void openDetail(Player player, Kit kit, int page) {
        player.getScheduler().execute(plugin, () -> doOpenDetail(player, kit, page), null, 0L);
    }

    private void doOpenDetail(Player player, Kit kit, int page) {
        PlayerKitData data = plugin.dataStore().get(player.getUniqueId());
        ConfigManager cfg = plugin.configManager();
        if (data == null || !data.loaded()) {
            player.sendMessage(cfg.message("menu.loading"));
            return;
        }
        // 点击负载由客户端伪造：无权限时不允许打开详情，避免窥探未授权礼包的奖励与状态
        if (!Permissions.canClaim(player, kit.permission())) {
            doOpenMain(player, page);
            return;
        }
        Map<PeriodType, String> periodKeys =
                plugin.limitService().currentPeriodKeys(System.currentTimeMillis());
        List<DialogBody> bodies = new ArrayList<>();
        bodies.add(DialogBody.item(kit.icon()).showTooltip(true).build());
        bodies.add(DialogBody.plainMessage(detailStatus(cfg, kit, data,
                Permissions.canBypassCooldown(player), Permissions.canBypassLimit(player), periodKeys)));
        bodies.add(DialogBody.plainMessage(cfg.message("menu.rewards-header")));
        for (Reward r : kit.rewards()) {
            bodies.add(DialogBody.plainMessage(
                    cfg.message("menu.reward-line", null, "preview", cfg.rewardPreview(r))));
        }

        ActionButton yes = ActionButton.builder(cfg.message("menu.claim-button"))
                .action(DialogAction.customClick(CLICK_KEY, payload(ClickPayload.CLAIM, kit.id(), page)))
                .build();
        ActionButton no = ActionButton.builder(cfg.message("menu.return-button"))
                .action(DialogAction.customClick(CLICK_KEY, payload(ClickPayload.BACK, "", page)))
                .build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(kit.displayName())
                        .body(bodies)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(yes, no)));
        player.showDialog(dialog);
    }

    /** 领取结果反馈页。backPage 为返回主菜单时应停留的页码。可从任意线程调用。 */
    public void feedback(Player player, Component message, int backPage) {
        player.getScheduler().execute(plugin,
                () -> doFeedback(player, message, backPage), null, 0L);
    }

    private void doFeedback(Player player, Component message, int backPage) {
        ConfigManager cfg = plugin.configManager();
        ActionButton ok = ActionButton.builder(cfg.message("menu.back-button"))
                .action(DialogAction.customClick(CLICK_KEY, payload(ClickPayload.BACK, "", backPage)))
                .build();
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(cfg.message("menu.feedback-title"))
                        .body(List.of(DialogBody.plainMessage(message)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.notice(ok)));
        player.showDialog(dialog);
    }

    /** 根据领取结果生成反馈消息。 */
    public Component resultMessage(LimitResult result, Kit kit, PlayerKitData data) {
        ConfigManager cfg = plugin.configManager();
        String kitName = kit.plainName();
        return switch (result) {
            case OK -> cfg.message("claim.success", Map.of("kit", kitName));
            case COOLDOWN -> cfg.message("claim.cooldown", Map.of("kit", kitName), "time",
                    cfg.duration(plugin.limitService().remainingCooldown(kit, data)));
            case MAX_CLAIMS -> cfg.message("claim.max-claims", Map.of("kit", kitName));
            case PERIOD_EXCEEDED -> cfg.message("claim.period-exceeded", Map.of("kit", kitName));
            case NO_PERMISSION -> cfg.message("claim.no-permission");
            case DATA_LOADING -> cfg.message("menu.loading");
            case FAILED -> cfg.message("claim.failed", Map.of("kit", kitName));
            case CANCELLED -> Component.empty();
        };
    }

    // ---- 内部构建辅助 ----

    private ActionButton kitButton(PlayerKitData data, Kit kit, int page,
                                   Map<PeriodType, String> periodKeys) {
        return ActionButton.builder(kit.displayName())
                .tooltip(buildTooltip(kit, data, periodKeys))
                .width(150)
                .action(DialogAction.customClick(CLICK_KEY, payload(ClickPayload.SELECT, kit.id(), page)))
                .build();
    }

    private ActionButton navButton(Component text, String action, int page) {
        return ActionButton.builder(text)
                .width(80)
                .action(DialogAction.customClick(CLICK_KEY, payload(action, "", page)))
                .build();
    }

    private Component detailStatus(ConfigManager cfg, Kit kit, PlayerKitData data,
                                   boolean bypassCd, boolean bypassLimit,
                                   Map<PeriodType, String> periodKeys) {
        LimitResult r = plugin.limitService().checkLimits(kit, data, bypassCd, bypassLimit, periodKeys);
        return switch (r) {
            case OK -> cfg.message("menu.detail-status.available");
            case COOLDOWN -> cfg.message("menu.detail-status.cooldown", null, "time",
                    cfg.duration(plugin.limitService().remainingCooldown(kit, data)));
            case MAX_CLAIMS -> cfg.message("menu.detail-status.max-claims");
            case PERIOD_EXCEEDED -> cfg.message("menu.detail-status.period-exceeded");
            default -> cfg.message("menu.detail-status.unknown");
        };
    }

    /** 组装多行 tooltip（直接拼 Component，避免序列化往返；周期 key 由调用方预计算复用）。 */
    private Component buildTooltip(Kit kit, PlayerKitData data, Map<PeriodType, String> periodKeys) {
        ConfigManager cfg = plugin.configManager();
        List<Component> lines = new ArrayList<>();
        lines.add(cfg.message("menu.tooltip.title", null, "kit", kit.displayName()));
        if (kit.cooldownMillis() > 0) {
            lines.add(cfg.message("menu.tooltip.cooldown", null, "duration",
                    cfg.duration(kit.cooldownMillis())));
        }
        if (kit.maxClaims() > 0) {
            KitRecord rec = data == null ? null : data.existingRecord(kit.id());
            int used = rec == null ? 0 : rec.totalClaims();
            lines.add(cfg.message("menu.tooltip.total",
                    Map.of("used", String.valueOf(used), "max", String.valueOf(kit.maxClaims()))));
        }
        KitRecord rec = data == null ? null : data.existingRecord(kit.id());
        for (Map.Entry<PeriodType, Integer> e : kit.periodLimits().entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            int used = rec == null ? 0 : rec.periodCount(e.getKey(), periodKeys.get(e.getKey()));
            lines.add(cfg.message("menu.tooltip.period", Map.of(
                    "period", cfg.plainPeriodName(e.getKey()),
                    "used", String.valueOf(used),
                    "max", String.valueOf(e.getValue()))));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private BinaryTagHolder payload(String action, String kitId, int page) {
        String enc = ClickPayload.encode(action, kitId, page);
        return BinaryTagHolder.binaryTagHolder("{d:\"" + enc + "\"}");
    }
}
