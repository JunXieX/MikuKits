package dev.junxiex.mikukits.data;

import dev.junxiex.mikukits.kit.PeriodType;
import dev.junxiex.mikukits.storage.Storage;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单个玩家对单个礼包的领取记录。
 * 字段仅由拥有该玩家的线程（玩家/区域线程）读写；对外只通过 {@link #snapshot} 生成
 * 不可变行快照投递到异步写回队列，从而避免跨线程访问内部 EnumMap 造成数据竞争。
 */
public final class KitRecord {

    /** 仅本类内部使用，外部不可见，字段可直接访问以免为纯数据类引入无谓的访问器。 */
    private static final class PeriodCounter {
        final String key;
        int count;

        PeriodCounter(String key, int count) {
            this.key = key;
            this.count = count;
        }
    }

    private long lastClaimAt;
    private int totalClaims;
    private final Map<PeriodType, PeriodCounter> periods = new EnumMap<>(PeriodType.class);

    public long lastClaimAt() {
        return lastClaimAt;
    }

    public int totalClaims() {
        return totalClaims;
    }

    /** 读取指定周期的当前计数，若周期 key 已变化则视为 0。 */
    public int periodCount(PeriodType type, String currentKey) {
        PeriodCounter c = periods.get(type);
        if (c == null || !c.key.equals(currentKey)) {
            return 0;
        }
        return c.count;
    }

    public void setLastClaimAt(long v) {
        this.lastClaimAt = v;
    }

    /** 从存储加载总领取次数。 */
    public void loadTotalClaims(int v) {
        this.totalClaims = v;
    }

    public void incrementTotalClaims() {
        this.totalClaims++;
    }

    /** 递增指定周期计数（自动处理跨周期重置）。 */
    public void incrementPeriod(PeriodType type, String currentKey) {
        PeriodCounter c = periods.get(type);
        if (c == null || !c.key.equals(currentKey)) {
            periods.put(type, new PeriodCounter(currentKey, 1));
        } else {
            c.count++;
        }
    }

    /**
     * 在当前线程生成不可变行快照（含序列化周期数据），供异步写回。
     * 必须在拥有该玩家的线程调用。
     */
    public Storage.KitRow snapshot(UUID uuid, String kitId) {
        return new Storage.KitRow(uuid, kitId, lastClaimAt, totalClaims, serializePeriods());
    }

    /** 形如 D=20261003:2;W=2026W40:3;M=202610:5 */
    private String serializePeriods() {
        if (periods.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<PeriodType, PeriodCounter> e : periods.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(shortCode(e.getKey())).append('=').append(e.getValue().key)
                    .append(':').append(e.getValue().count);
        }
        return sb.toString();
    }

    public void loadPeriods(String data) {
        periods.clear();
        if (data == null || data.isEmpty()) {
            return;
        }
        for (String part : data.split(";")) {
            int eq = part.indexOf('=');
            int colon = part.indexOf(':', eq);
            if (eq < 0 || colon < 0) {
                continue;
            }
            PeriodType type = fromShortCode(part.substring(0, eq));
            if (type == null) {
                continue;
            }
            String key = part.substring(eq + 1, colon);
            int count;
            try {
                count = Integer.parseInt(part.substring(colon + 1));
            } catch (NumberFormatException ex) {
                count = 0;
            }
            periods.put(type, new PeriodCounter(key, count));
        }
    }

    private static String shortCode(PeriodType t) {
        return switch (t) {
            case DAILY -> "D";
            case WEEKLY -> "W";
            case MONTHLY -> "M";
        };
    }

    private static PeriodType fromShortCode(String s) {
        return switch (s) {
            case "D" -> PeriodType.DAILY;
            case "W" -> PeriodType.WEEKLY;
            case "M" -> PeriodType.MONTHLY;
            default -> null;
        };
    }
}
