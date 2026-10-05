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

    /** 向玩家存款。Vault 不可用时静默跳过（仅在真正需要发放金钱时提示一次）。必须在玩家线程调用。 */
    public void deposit(Player player, double amount) {
        if (!available()) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("未检测到 Vault Economy，金钱奖励将被跳过。");
            }
            return;
        }
        try {
            EconomyBridge.deposit(player, amount);
        } catch (Throwable t) {
            plugin.getLogger().warning("Vault 存款失败: " + t.getMessage());
        }
    }
}
