package dev.junxiex.mikukits.vault;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Vault Economy 桥接。所有对 {@code net.milkbowl.vault.economy.Economy} 的引用
 * 都集中在这个类中，仅当 Vault 确实存在时才会被 {@link VaultHook} 调用。
 * <p>
 * 若 Vault 未安装，本类的首次加载/验证会抛 NoClassDefFoundError，
 * 由 VaultHook 的 try/catch(Throwable) 捕获并永久禁用金钱功能。
 */
final class EconomyBridge {

    private EconomyBridge() {
    }

    static boolean hasEconomy() {
        return Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class) != null;
    }

    /**
     * 向玩家存款。
     * <p>
     * Vault 的 {@code depositPlayer} 会返回 {@link net.milkbowl.vault.economy.EconomyResponse}，
     * 经济插件可以用它报告失败（例如账户被冻结、数额非法）。忽略返回值会把失败当成功，
     * 导致玩家没拿到钱却被扣掉领取次数，因此必须检查 {@code transactionSuccess()}。
     *
     * @return true 表示经济插件确认存款成功
     */
    static boolean deposit(Player player, double amount) {
        var rsp = Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp == null) {
            return false;
        }
        net.milkbowl.vault.economy.EconomyResponse response = rsp.getProvider().depositPlayer(player, amount);
        if (response == null) {
            return false;
        }
        if (!response.transactionSuccess()) {
            Bukkit.getLogger().warning("Vault 存款未成功: " + response.errorMessage);
            return false;
        }
        return true;
    }
}
