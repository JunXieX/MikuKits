package dev.junxiex.mikukits.reward;

import dev.junxiex.mikukits.MikuKitsPlugin;
import dev.junxiex.mikukits.api.MikuKitClaimEvent;
import dev.junxiex.mikukits.api.MikuKitPreClaimEvent;
import dev.junxiex.mikukits.data.LimitResult;
import dev.junxiex.mikukits.data.PlayerKitData;
import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.kit.reward.Reward;
import dev.junxiex.mikukits.util.Permissions;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * 领取发放编排。必须在拥有该玩家的线程（玩家/区域/实体线程）调用。
 * 流程：数据就绪校验 → 限制校验 → PreClaim 事件 → 发放奖励 → 记录 → 写回入队 → Claim 事件。
 */
public final class RewardDispatcher {

    private final MikuKitsPlugin plugin;

    public RewardDispatcher(MikuKitsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家主动领取（菜单点击）。次数/冷却是否绕过由玩家的 bypass 权限决定。
     *
     * @return 结果，供调用方反馈
     */
    public LimitResult dispatch(Player player, Kit kit) {
        return dispatch(player, kit, true);
    }

    /**
     * 进服自动领取：系统被动发放，必须始终遵守全部限制，不受 bypass 权限影响。
     * <p>
     * 若此处也采信 bypass 权限，拥有 {@code mikukits.bypass.limit} 或 {@code mikukits.admin}
     * 的玩家（典型如管理员）会因为 max-claims / period-limits 被整体跳过，导致每次进服都重复
     * 领取一遍（新手礼包会因此不断塞满管理员背包）。需要重复发放请用 /kits give 显式指定。
     */
    public LimitResult dispatchAutoClaim(Player player, Kit kit) {
        return dispatch(player, kit, false);
    }

    /**
     * 领取核心流程。
     *
     * @param honorBypass 是否采信玩家的 bypass 权限；仅玩家主动领取时为 true
     */
    private LimitResult dispatch(Player player, Kit kit, boolean honorBypass) {
        PlayerKitData data = plugin.dataStore().get(player.getUniqueId());
        if (data == null || !data.loaded()) {
            // 数据尚未加载完成；调用方（菜单/自动领取）应在加载完成后再触发
            return LimitResult.DATA_LOADING;
        }

        boolean bypassCooldown = honorBypass && Permissions.canBypassCooldown(player);
        boolean bypassLimit = honorBypass && Permissions.canBypassLimit(player);

        LimitResult result = plugin.limitService().check(player, kit, data, bypassCooldown, bypassLimit);
        MikuKitPreClaimEvent pre = new MikuKitPreClaimEvent(player, kit, result);
        plugin.getServer().getPluginManager().callEvent(pre);
        if (pre.isCancelled()) {
            return LimitResult.CANCELLED;
        }
        if (!result.allowed()) {
            return result;
        }

        if (!grantAll(player, kit)) {
            // 全部奖励发放失败：不消耗领取次数，玩家可重试
            plugin.getLogger().warning("礼包 " + kit.id() + " 的全部奖励发放失败，本次不消耗领取次数");
            return LimitResult.FAILED;
        }

        plugin.limitService().recordClaim(kit, data);
        plugin.dataStore().enqueue(data, kit.id());
        plugin.getServer().getPluginManager().callEvent(new MikuKitClaimEvent(player, kit));
        return LimitResult.OK;
    }

    /**
     * 管理员强制发放（绕过全部限制，仍记录）。
     *
     * @param feedback 接收结果反馈的命令发送者（管理员/控制台），与目标玩家解耦
     */
    public void forceGive(Player player, Kit kit, CommandSender feedback) {
        if (!player.isOnline()) {
            feedback.sendMessage(giveMessage("command.give-offline", player, kit));
            return;
        }
        PlayerKitData data = plugin.dataStore().get(player.getUniqueId());
        if (data == null || !data.loaded()) {
            feedback.sendMessage(giveMessage("command.give-not-ready", player, kit));
            return;
        }
        if (!grantAll(player, kit)) {
            feedback.sendMessage(giveMessage("command.give-reward-failed", player, kit));
            return;
        }
        plugin.limitService().recordClaim(kit, data);
        plugin.dataStore().enqueue(data, kit.id());
        feedback.sendMessage(giveMessage("command.give-success", player, kit));
    }

    /**
     * 逐个发放奖励；单个奖励失败不影响其余奖励与领取记录。
     * 背包溢出提示在此处统一发送一次，避免同一礼包多件物品溢出时刷屏。
     *
     * @return 是否至少成功发放了一项（未配置奖励时视为成功）
     */
    private boolean grantAll(Player player, Kit kit) {
        if (kit.rewards().isEmpty()) {
            return true;
        }
        int succeeded = 0;
        long overflowed = 0;
        for (Reward reward : kit.rewards()) {
            try {
                overflowed += reward.grant(player, plugin);
                succeeded++;
            } catch (Exception ex) {
                plugin.getLogger().warning("发放礼包 " + kit.id() + " 的奖励 "
                        + reward.getClass().getSimpleName() + " 失败: " + ex.getMessage());
            }
        }
        if (overflowed > 0) {
            player.sendMessage(plugin.configManager().message("claim.inventory-full",
                    Map.of("kit", kit.plainName(), "amount", String.valueOf(overflowed))));
        }
        return succeeded > 0;
    }

    private Component giveMessage(String key, Player player, Kit kit) {
        return plugin.configManager().message(key, Map.of("player", player.getName(), "kit", kit.id()));
    }
}
