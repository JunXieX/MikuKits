package dev.junxiex.mikukits.vault;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Vault 经济挂钩。
 * <p>
 * 关键设计：本类不直接引用 {@code net.milkbowl.vault.economy.Economy} 类型，
 * 而是通过 {@link EconomyBridge} 间接访问。这样当服务器未安装 Vault 时，
 * 对 VaultHook 的调用只会触发桥接类的加载失败并被捕获，
 * 不会污染调用方（如 MoneyReward）的类验证，避免运行期 NoClassDefFoundError。
 * <p>
 * 缺失时优雅降级：金钱奖励静默跳过。
 */
public final class VaultHook {

    private final Plugin plugin;
    private volatile boolean available;
    private volatile boolean resolved;
    private volatile boolean warned;

    public VaultHook(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 探测 Vault + Economy 是否可用（幂等，线程安全）。仅查询状态，不产生日志。 */
    public boolean available() {
        if (!resolved) {
            synchronized (this) {
                if (!resolved) {
                    boolean ok = false;
                    try {
                        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                            ok = EconomyBridge.hasEconomy();
                        }
                    } catch (Throwable ignored) {
                        // Vault 类缺失（NoClassDefFoundError）→ 视为不可用
                        ok = false;
                    }
                    available = ok;
                    resolved = true;
                }
            }
        }
        return available;
    }

    /**
     * 向玩家存款。
     * <p>
     * 返回是否真的发放成功。调用方（{@link dev.junxiex.mikukits.kit.reward.MoneyReward}）必须据此
     * 判定奖励是否落地：若此处静默返回，则该奖励会被计为"发放成功"，玩家没拿到钱却照常消耗领取次数。
     *
     * @return true 表示存款已执行；false 表示 Vault 不可用或存款抛错
     */
    public boolean deposit(Player player, double amount) {
        if (!available()) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("未检测到 Vault Economy，金钱奖励将被跳过。");
            }
            return false;
        }
        try {
            return EconomyBridge.deposit(player, amount);
        } catch (Throwable t) {
            plugin.getLogger().warning("Vault 存款失败: " + t.getMessage());
            return false;
        }
    }
}
