package dev.junxiex.mikukits.listener;

import dev.junxiex.mikukits.MikuKitsPlugin;
import dev.junxiex.mikukits.data.LimitResult;
import dev.junxiex.mikukits.data.PlayerKitData;
import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.util.Permissions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;

/**
 * 玩家进出处理：异步加载数据 → 回投玩家线程 → 自动领取。
 */
public final class PlayerConnectionListener implements Listener {

    private final MikuKitsPlugin plugin;

    public PlayerConnectionListener(MikuKitsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var uuid = player.getUniqueId();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            PlayerKitData data;
            try {
                data = plugin.dataStore().loadFromStorage(uuid);
            } catch (Exception ex) {
                // 关服时连接可能已被 close，属预期情况而非故障；且此时玩家即将离线，无需提示
                if (plugin.getServer().isStopping()) {
                    return;
                }
                // 加载/写回失败必须在此兜住：否则异常会终止整个任务，
                // 数据永不入缓存，玩家会永久停留在"数据加载中"且控制台毫无线索
                plugin.getLogger().severe("加载玩家 " + uuid + " 的礼包数据失败: " + ex.getMessage()
                        + "（该玩家本次会话无法领取礼包，请检查数据库）");
                player.getScheduler().run(plugin, t -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.configManager().message("menu.load-failed"));
                    }
                }, null);
                return;
            }
            // 回投到玩家线程：放入缓存并处理自动领取
            player.getScheduler().run(plugin, t -> {
                // 快速重连/已离线：丢弃本次加载结果（数据未改动，无需写回），避免缓存泄漏与对失效玩家操作
                if (!player.isOnline()) {
                    return;
                }
                plugin.dataStore().put(data);
                handleAutoClaim(player);
            }, null);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerKitData data = plugin.dataStore().get(player.getUniqueId());
        if (data != null) {
            plugin.dataStore().enqueueAll(data);
        }
        plugin.dataStore().remove(player.getUniqueId());
    }

    /**
     * 进服自动领取：遍历 auto-claim 礼包，通过限制校验则发放。
     * 走 dispatchAutoClaim（不采信 bypass 权限），否则管理员等有 bypass 的玩家每次进服都会重复领取。
     */
    private void handleAutoClaim(Player player) {
        // 通配/管理员判定只算一次，循环内只查各礼包的专属节点
        boolean canAll = Permissions.canClaimAll(player);
        for (Kit kit : plugin.kitManager().autoClaimKits()) {
            if (!Permissions.canClaim(player, kit.permission(), canAll)) {
                continue;
            }
            LimitResult r = plugin.dispatcher().dispatchAutoClaim(player, kit);
            if (r == LimitResult.OK) {
                player.sendMessage(plugin.configManager().message("claim.auto-claim",
                        Map.of("kit", kit.plainName())));
            }
        }
    }
}
