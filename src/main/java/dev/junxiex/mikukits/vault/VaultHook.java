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
    private volatile boolean warned;

    public VaultHook(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 探测 Vault + Economy 是否可用（线程安全）。
     * <p>
     * 只缓存"可用"这一正结果；不可用时每次都重新探测。原因：经济插件（Essentials 等）
     * 可能比本插件晚加载，或服主在运行期新装/卸载经济插件。若把 false 永久缓存，
     * 金钱奖励会一直被判为不可用，配合"发放失败不消耗次数"的逻辑，该礼包将永远无法领取。
     * 探测本身只是一次 ServicesManager 的 map 查询，开销可忽略。
     */
    public boolean available() {
        if (available) {
            return true;
        }
        synchronized (this) {
            if (available) {
                return true;
            }
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
            return ok;
        }
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
                plugin.getLogger().warning("未检测到 Vault Economy，金钱奖励无法发放（本次领取按失败处理，不消耗次数）。");
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
