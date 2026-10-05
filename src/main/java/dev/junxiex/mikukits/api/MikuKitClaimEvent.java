package dev.junxiex.mikukits.api;

import dev.junxiex.mikukits.kit.Kit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 领取成功、奖励发放完成后触发。
 */
public class MikuKitClaimEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Kit kit;

    public MikuKitClaimEvent(@NotNull Player player, @NotNull Kit kit) {
        this.player = player;
        this.kit = kit;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
    public Kit getKit() {
        return kit;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
