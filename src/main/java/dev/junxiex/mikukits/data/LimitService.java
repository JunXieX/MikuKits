package dev.junxiex.mikukits.data;

import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.kit.PeriodType;
import dev.junxiex.mikukits.util.Permissions;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/**
 * 领取限制校验。纯内存计算，无 IO，可在任意线程安全调用（读取 volatile 字段）。
 */
public final class LimitService {

    private final ZoneId zone = ZoneId.systemDefault();

    /**
     * 校验玩家能否领取礼包（含权限）。
     *
     * @param bypassCooldown 是否绕过冷却
     * @param bypassLimit    是否绕过次数限制
     */
    public LimitResult check(Player player, Kit kit, PlayerKitData data,
                             boolean bypassCooldown, boolean bypassLimit) {
        if (!Permissions.canClaim(player, kit.permission())) {
            return LimitResult.NO_PERMISSION;
        }
        return checkLimits(kit, data, bypassCooldown, bypassLimit);
    }

    /**
     * 预计算某一时刻各周期对应的 key。一次菜单渲染/批量校验中 now 恒定，
     * 预先算一遍即可供所有礼包复用，避免逐礼包重复分配时间对象。
     */
    public Map<PeriodType, String> currentPeriodKeys(long now) {
        Map<PeriodType, String> keys = new EnumMap<>(PeriodType.class);
        for (PeriodType type : PeriodType.values()) {
            keys.put(type, type.keyOf(now, zone));
        }
        return keys;
    }

    /** 仅校验冷却与次数限制（不含权限）。菜单渲染用，player 已知有权限。 */
    public LimitResult checkLimits(Kit kit, PlayerKitData data,
                                   boolean bypassCooldown, boolean bypassLimit) {
        return checkLimits(kit, data, bypassCooldown, bypassLimit, null);
    }

    /**
     * 仅校验冷却与次数限制（不含权限）。
     *
     * @param periodKeys 预计算的周期 key；为 null 时按当前时刻现算
     */
    public LimitResult checkLimits(Kit kit, PlayerKitData data,
                                   boolean bypassCooldown, boolean bypassLimit,
                                   Map<PeriodType, String> periodKeys) {
        if (data == null || !data.loaded()) {
            return LimitResult.DATA_LOADING;
        }
        KitRecord rec = data.existingRecord(kit.id());
        long now = System.currentTimeMillis();

        if (!bypassCooldown && kit.cooldownMillis() > 0 && rec != null) {
            long elapsed = now - rec.lastClaimAt();
            // elapsed < 0 表示系统时钟被回拨（NTP 校正、手动改时间）。此时按"已过冷却"处理：
            // 若照常比较，负的 elapsed 永远小于 cooldown，玩家会被卡到时钟追上为止，表现为冷却永久不结束
            if (elapsed >= 0 && elapsed < kit.cooldownMillis()) {
                return LimitResult.COOLDOWN;
            }
        }
        if (!bypassLimit) {
            if (kit.maxClaims() > 0 && rec != null && rec.totalClaims() >= kit.maxClaims()) {
                return LimitResult.MAX_CLAIMS;
            }
            for (var e : kit.periodLimits().entrySet()) {
                int limit = e.getValue();
                if (limit <= 0) {
                    continue;
                }
                PeriodType type = e.getKey();
                String key = periodKeys != null ? periodKeys.get(type) : type.keyOf(now, zone);
                int used = rec == null ? 0 : rec.periodCount(type, key);
                if (used >= limit) {
                    return LimitResult.PERIOD_EXCEEDED;
                }
            }
        }
        return LimitResult.OK;
    }

    /** 剩余冷却毫秒（0 表示无冷却）。 */
    public long remainingCooldown(Kit kit, PlayerKitData data) {
        if (kit.cooldownMillis() <= 0 || data == null) {
            return 0L;
        }
        KitRecord rec = data.existingRecord(kit.id());
        if (rec == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - rec.lastClaimAt();
        // 时钟回拨时 elapsed 为负，按"无冷却"处理，避免剩余时间被算成一个巨大的正数
        if (elapsed < 0) {
            return 0L;
        }
        return Math.max(0L, kit.cooldownMillis() - elapsed);
    }

    /** 记录一次成功领取（仅玩家线程调用）。 */
    public void recordClaim(Kit kit, PlayerKitData data) {
        long now = System.currentTimeMillis();
        KitRecord rec = data.record(kit.id());
        rec.setLastClaimAt(now);
        rec.incrementTotalClaims();
        for (PeriodType type : kit.periodLimits().keySet()) {
            if (kit.periodLimits().get(type) > 0) {
                rec.incrementPeriod(type, type.keyOf(now, zone));
            }
        }
    }
}
