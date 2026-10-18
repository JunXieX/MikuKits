package dev.junxiex.mikukits.kit.reward;

import dev.junxiex.mikukits.MikuKitsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;

/**
 * Vault 金钱奖励。
 * <p>
 * 通过 VaultHook 间接访问 Economy，避免 Vault 缺失时本类加载即崩溃。
 * Economy 非线程安全，必须在玩家线程调用（grant 已满足）。
 * <p>
 * 预览文本在构造时一次性格式化，避免每次菜单渲染重复计算。
 */
public final class MoneyReward implements Reward {

    private final double amount;
    private final Map<String, String> previewArgs;

    public MoneyReward(double amount) {
        this.amount = amount;
        this.previewArgs = Map.of("amount", String.format(Locale.ROOT, "%.2f", amount));
    }

    @Override
    public int grant(Player player, Plugin plugin) {
        // Vault 不可用时必须让本次发放"算作失败"：否则 RewardDispatcher 会把它计为成功，
        // 玩家一分钱没拿到却照常被扣掉一次领取机会。抛出后由调用方捕获并记录，不消耗次数。
        if (!((MikuKitsPlugin) plugin).vaultHook().deposit(player, amount)) {
            throw new IllegalStateException("Vault Economy 不可用，无法发放金钱奖励");
        }
        return 0; // 金钱不进背包，不存在溢出
    }

    @Override
    public String previewKey() {
        return "money";
    }

    @Override
    public Map<String, String> previewArgs() {
        return previewArgs;
    }
}
