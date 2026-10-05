package dev.junxiex.mikukits.api;

import dev.junxiex.mikukits.data.LimitResult;
import dev.junxiex.mikukits.kit.Kit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 领取前事件（可取消）。第三方插件可在此拦截领取或自定义提示。
 */
public class MikuKitPreClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Kit kit;
    private final LimitResult result;
    private boolean cancelled;

    public MikuKitPreClaimEvent(@NotNull Player player, @NotNull Kit kit, @NotNull LimitResult result) {
        this.player = player;
        this.kit = kit;
        this.result = result;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
    public Kit getKit() {
        return kit;
    }

    /** 限制校验结果；若为 OK 表示各项限制均通过。 */
    @NotNull
    public LimitResult getResult() {
        return result;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
