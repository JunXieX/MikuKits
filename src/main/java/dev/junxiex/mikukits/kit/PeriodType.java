package dev.junxiex.mikukits.kit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;

/**
 * 领取次数限制的周期类型。每个周期有独立的 key，key 变化即视为重置。
 * <p>
 * 周期边界按传入的 {@link ZoneId} 计算；每周采用 ISO-8601 规则（周一为一周第一天），
 * 与中国大陆的"每周"习惯一致（旧版使用 {@code Locale.US} 的周日起始规则，已纠正）。
 */
public enum PeriodType {

    DAILY {
        @Override
        public String keyOf(long epochMillis, ZoneId zone) {
            LocalDate d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
            return pad(d.getYear(), 4) + pad(d.getMonthValue(), 2) + pad(d.getDayOfMonth(), 2);
        }
    },
    WEEKLY {
        @Override
        public String keyOf(long epochMillis, ZoneId zone) {
            LocalDateTime dt = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime();
            return pad(dt.get(WeekFields.ISO.weekBasedYear()), 4) + "W"
                    + pad(dt.get(WeekFields.ISO.weekOfWeekBasedYear()), 2);
        }
    },
    MONTHLY {
        @Override
        public String keyOf(long epochMillis, ZoneId zone) {
            LocalDate d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
            return pad(d.getYear(), 4) + pad(d.getMonthValue(), 2);
        }
    };

    /** 返回当前时刻所属周期的字符串标识。 */
    public abstract String keyOf(long epochMillis, ZoneId zone);

    /** 左侧补零；避免 {@code String.format} 的格式化解析开销（该方法在菜单渲染/限制校验中高频调用）。 */
    private static String pad(int value, int width) {
        String s = Integer.toString(value);
        int missing = width - s.length();
        return missing <= 0 ? s : "0".repeat(missing) + s;
    }
}
