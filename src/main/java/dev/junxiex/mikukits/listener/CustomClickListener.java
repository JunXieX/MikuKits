package dev.junxiex.mikukits.listener;

import dev.junxiex.mikukits.MikuKitsPlugin;
import dev.junxiex.mikukits.data.LimitResult;
import dev.junxiex.mikukits.dialog.ClickPayload;
import dev.junxiex.mikukits.dialog.DialogMenuService;
import dev.junxiex.mikukits.kit.Kit;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理 Dialog 菜单的 customClick 事件。事件在玩家连接线程触发，可直接操作玩家。
 * <p>
 * 该事件由客户端触发，可被伪造并高频重放，因此按玩家做最小点击间隔节流，
 * 防止无冷却/无次数上限的礼包被刷取（顺带抑制反馈刷屏与写回队列膨胀）。
 */
public final class CustomClickListener implements Listener {

    private final MikuKitsPlugin plugin;
    /** 玩家 UUID → 上次处理点击的时间戳（毫秒）。 */
    private final Map<UUID, Long> lastClickAt = new ConcurrentHashMap<>();

    public CustomClickListener(MikuKitsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent e) {
        if (!DialogMenuService.CLICK_KEY.equals(e.getIdentifier())) {
            return;
        }
        Player player;
        if (e.getCommonConnection() instanceof PlayerGameConnection game) {
            player = game.getPlayer();
        } else {
            return; // 配置阶段连接，忽略
        }
        if (player == null) {
            return;
        }
        if (isThrottled(player)) {
            return;
        }

        String encoded = ClickPayload.decode(e.getTag() == null ? null : e.getTag().string());
        ClickPayload.Parsed p = ClickPayload.parse(encoded);
        DialogMenuService menu = plugin.menuService();

        switch (p.action()) {
            case ClickPayload.SELECT -> {
                Kit kit = plugin.kitManager().get(p.kitId());
                if (kit != null) {
                    menu.openDetail(player, kit, p.page());
                } else {
                    menu.openMain(player, p.page());
                }
            }
            case ClickPayload.CLAIM -> {
                Kit kit = plugin.kitManager().get(p.kitId());
                if (kit != null) {
                    LimitResult r = plugin.dispatcher().dispatch(player, kit);
                    if (r != LimitResult.CANCELLED) {
                        menu.feedback(player, menu.resultMessage(r, kit,
                                plugin.dataStore().get(player.getUniqueId())), p.page());
                    }
                }
            }
            // PREV/NEXT/BACK 与未知动作都是"回到指定页的主菜单"
            default -> menu.openMain(player, p.page());
        }
    }

    /** 节流状态下清理，避免长期运行后残留已离线玩家的记录。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastClickAt.remove(e.getPlayer().getUniqueId());
    }

    /** 超出最小点击间隔则丢弃本次事件（静默，避免用消息反过来被刷屏）。 */
    private boolean isThrottled(Player player) {
        int throttle = plugin.configManager().clickThrottleMillis();
        if (throttle <= 0) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        // compute 对同一 key 原子执行，消除"读取-判断-写入"竞态，避免并发的伪造包同时通过节流
        boolean[] rejected = {false};
        lastClickAt.compute(uuid, (key, previous) -> {
            if (previous != null && now - previous < throttle) {
                // 仅记录被接受的点击：否则持续刷包会把时间戳一路顶高，让正常玩家被无限锁死
                rejected[0] = true;
                return previous;
            }
            return now;
        });
        return rejected[0];
    }
}
