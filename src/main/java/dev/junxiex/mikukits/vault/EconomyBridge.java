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

    static void deposit(Player player, double amount) {
        var rsp = Bukkit.getServicesManager()
                .getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp != null) {
            rsp.getProvider().depositPlayer(player, amount);
        }
    }
}
