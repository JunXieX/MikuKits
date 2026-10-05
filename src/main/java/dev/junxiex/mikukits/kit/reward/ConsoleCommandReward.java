package dev.junxiex.mikukits.kit.reward;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * 控制台指令奖励。占位符 {player}/{world}/{x}/{y}/{z} 在发放前替换。
 * 实际执行投递到 GlobalRegionScheduler（控制台命令是全局操作）。
 */
public record ConsoleCommandReward(String command) implements Reward {

    @Override
    public int grant(Player player, Plugin plugin) {
        // dispatchCommand 不接受前导斜杠，容错处理配置里多写的 "/"
        String resolved = PlaceholderResolver.resolve(command, player);
        final String toRun = resolved.startsWith("/") ? resolved.substring(1) : resolved;
        plugin.getServer().getGlobalRegionScheduler()
                .run(plugin, task -> plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), toRun));
        return 0; // 指令不进背包，不存在溢出
    }

    @Override
    public String previewKey() {
        return "console-command";
    }

    @Override
    public Map<String, String> previewArgs() {
        return Map.of("command", command);
    }
}
