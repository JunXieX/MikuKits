package dev.junxiex.mikukits.kit.reward;

import org.bukkit.entity.Player;

/**
 * 指令占位符替换（不依赖 PlaceholderAPI）。
 */
final class PlaceholderResolver {

    private PlaceholderResolver() {
    }

    static String resolve(String input, Player player) {
        if (input == null || input.indexOf('{') < 0) {
            return input;
        }
        return input
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{world}", player.getWorld().getName())
                .replace("{x}", String.valueOf(player.getLocation().getBlockX()))
                .replace("{y}", String.valueOf(player.getLocation().getBlockY()))
                .replace("{z}", String.valueOf(player.getLocation().getBlockZ()));
    }
}
