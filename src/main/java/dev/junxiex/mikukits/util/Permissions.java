package dev.junxiex.mikukits.util;

import org.bukkit.entity.Player;

/**
 * 权限判定统一入口。所有权限节点字符串集中于此，避免散落到各处出现不一致。
 * <p>
 * Bukkit 原生权限系统不做通配符继承，{@code mikukits.kit.*} 只是一个字面节点，
 * 不会自动授予 {@code mikukits.kit.starter}。因此显式检查通配节点，
 * 使纯 Bukkit 下授予 {@code mikukits.kit.*} 也能领取全部礼包（LuckPerms 等通配符插件同样兼容）。
 */
public final class Permissions {

    public static final String WILDCARD = "mikukits.kit.*";
    public static final String ADMIN = "mikukits.admin";
    /** 礼包权限节点前缀，完整节点为 {@code mikukits.kit.<id>}。 */
    public static final String KIT_PREFIX = "mikukits.kit.";
    public static final String BYPASS_COOLDOWN = "mikukits.bypass.cooldown";
    public static final String BYPASS_LIMIT = "mikukits.bypass.limit";

    private Permissions() {
    }

    /** 是否持有"全部礼包"级别的权限（通配或管理员），可在遍历礼包前只算一次。 */
    public static boolean canClaimAll(Player player) {
        return player.hasPermission(WILDCARD) || player.hasPermission(ADMIN);
    }

    /** 是否可领取指定权限的礼包。 */
    public static boolean canClaim(Player player, String kitPermission) {
        return player.hasPermission(kitPermission) || canClaimAll(player);
    }

    /**
     * 已预先算出 {@link #canClaimAll(Player)} 时的快速判定，避免在礼包循环里重复查询通配/管理员节点。
     */
    public static boolean canClaim(Player player, String kitPermission, boolean canAll) {
        return canAll || player.hasPermission(kitPermission);
    }

    /** 是否可绕过冷却时间。 */
    public static boolean canBypassCooldown(Player player) {
        return player.hasPermission(BYPASS_COOLDOWN) || player.hasPermission(ADMIN);
    }

    /** 是否可绕过次数限制。 */
    public static boolean canBypassLimit(Player player) {
        return player.hasPermission(BYPASS_LIMIT) || player.hasPermission(ADMIN);
    }
}
