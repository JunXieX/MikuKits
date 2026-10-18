package dev.junxiex.mikukits.kit.reward;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * 玩家指令奖励。以玩家身份执行，占位符在发放前替换。
 * grant 已在玩家线程被调用，直接 performCommand。
 */
public record PlayerCommandReward(String command) implements Reward {

    @Override
    public int grant(Player player, Plugin plugin) {
        String resolved = PlaceholderResolver.resolve(command, player);
        // performCommand 不接受前导斜杠，容错处理配置里多写的 "/"
        // 返回值表示指令是否真正执行成功（未知指令/被拒绝时为 false）。忽略它会把失败当成功，
        // 玩家什么都没拿到却被扣掉领取次数，因此必须抛出交由调用方计入失败。
        if (!player.performCommand(stripSlash(resolved))) {
            throw new IllegalStateException("玩家指令执行失败（指令不存在或无权限）: " + resolved);
        }
        return 0; // 指令不进背包，不存在溢出
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    @Override
    public String previewKey() {
        return "player-command";
    }

    @Override
    public Map<String, String> previewArgs() {
        return Map.of("command", command);
    }
}
