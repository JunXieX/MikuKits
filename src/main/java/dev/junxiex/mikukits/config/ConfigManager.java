package dev.junxiex.mikukits.config;

import dev.junxiex.mikukits.MikuKitsPlugin;
import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.kit.PeriodType;
import dev.junxiex.mikukits.kit.reward.Reward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 加载 config.yml / messages.yml / kits/*.yml。
 * <p>
 * 运行期读取的全部配置都集中在单个不可变 {@link Snapshot} 里，reload 时一次性发布，
 * 保证并发渲染菜单的线程不会读到"新消息 + 旧页码"这类新旧混合状态。
 */
public final class ConfigManager {

    /** 预编译：{@link #plain(Component)} 在菜单渲染中高频调用，避免每次重新编译正则。 */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 20;
    private static final int FLUSH_SECONDS_MIN = 5;
    private static final int DEFAULT_PAGE_SIZE = 8;
    private static final int DEFAULT_FLUSH_SECONDS = 30;
    private static final int DEFAULT_THROTTLE_MS = 200;
    /** 渲染结果缓存上限；超出后不再新增（只读旧条目），避免玩家名等非常量文本无限堆积。 */
    private static final int RENDER_CACHE_MAX = 2048;

    /** 一次性发布的配置快照。 */
    private record Snapshot(Map<String, String> messages,
                            Map<String, Component> constants,
                            Map<PeriodType, String> periodNames,
                            int menuPageSize,
                            int flushIntervalSeconds,
                            int clickThrottleMillis) {
    }

    private final MikuKitsPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final KitConfigParser parser;
    /** 最终渲染文本 → 解析后的 Component。键已完全替换占位符，命中即等价，reload 不会产生陈旧渲染。 */
    private final Map<String, Component> renderCache = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of(),
            DEFAULT_PAGE_SIZE, DEFAULT_FLUSH_SECONDS, DEFAULT_THROTTLE_MS);

    public ConfigManager(MikuKitsPlugin plugin) {
        this.plugin = plugin;
        this.parser = new KitConfigParser(plugin.getLogger());
    }

    /** 重新加载全部配置，返回解析出的礼包表。 */
    public Map<String, Kit> loadAll() {
        saveDefault("config.yml");
        saveDefault("messages.yml");
        File kitsDir = new File(plugin.getDataFolder(), "kits");
        if (!kitsDir.exists()) {
            kitsDir.mkdirs();
            plugin.saveResource("kits/starter.yml", false);
            plugin.saveResource("kits/daily.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        int rawPageSize = config.getInt("menu.page-size", DEFAULT_PAGE_SIZE);
        if (rawPageSize < PAGE_SIZE_MIN || rawPageSize > PAGE_SIZE_MAX) {
            plugin.getLogger().warning("config.yml 的 menu.page-size=" + rawPageSize + " 超出允许范围 "
                    + PAGE_SIZE_MIN + "-" + PAGE_SIZE_MAX + "，已自动钳制");
        }
        int menuPageSize = Math.max(PAGE_SIZE_MIN, Math.min(PAGE_SIZE_MAX, rawPageSize));
        int flushIntervalSeconds = Math.max(FLUSH_SECONDS_MIN,
                config.getInt("storage.flush-interval-seconds", DEFAULT_FLUSH_SECONDS));
        int clickThrottleMillis = Math.max(0, config.getInt("menu.click-throttle-ms", DEFAULT_THROTTLE_MS));

        Map<String, String> newMessages = new HashMap<>();
        YamlConfiguration msg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        for (String key : msg.getKeys(true)) {
            String value = msg.getString(key);
            if (value != null) {
                newMessages.put(key, value);
            }
        }
        Map<String, String> messages = Map.copyOf(newMessages);

        // 不含占位符 {xxx} 的模板渲染结果恒定，预解析后可跳过每次的 MiniMessage 解析
        Map<String, Component> constants = new HashMap<>();
        for (Map.Entry<String, String> e : messages.entrySet()) {
            if (e.getValue().indexOf('{') < 0) {
                constants.put(e.getKey(), mm.deserialize(applyPrefix(e.getValue(), messages)));
            }
        }

        // 周期名纯文本预计算，避免每次渲染 tooltip 都序列化 + 剥标签
        Map<PeriodType, String> periodNames = new EnumMap<>(PeriodType.class);
        for (PeriodType type : PeriodType.values()) {
            String raw = messages.get("menu.tooltip.period-name." + type.name().toLowerCase(Locale.ROOT));
            periodNames.put(type, raw == null
                    ? type.name()
                    : plain(mm.deserialize(applyPrefix(raw, messages))));
        }

        // 配置已变，旧渲染缓存一律作废（键虽等价安全，但清掉以免长期占用）
        renderCache.clear();
        snapshot = new Snapshot(messages, Map.copyOf(constants), Map.copyOf(periodNames),
                menuPageSize, flushIntervalSeconds, clickThrottleMillis);

        Map<String, Kit> kits = new HashMap<>();
        File[] files = kitsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName().substring(0, f.getName().length() - 4);
                try {
                    YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                    Kit kit = parser.parse(name, yml);
                    kits.put(kit.id(), kit);
                } catch (Exception ex) {
                    plugin.getLogger().severe("解析礼包 " + f.getName() + " 失败: " + ex.getMessage());
                }
            }
        }
        return kits;
    }

    /** 取 MiniMessage 消息并渲染为 Component；缺失时使用可配置的 error.missing-message 兜底。 */
    public Component message(String key, Map<String, String> placeholders) {
        Component constant = snapshot.constants().get(key);
        if (constant != null) {
            // 常量消息不含占位符，占位符参数对其无意义
            return constant;
        }
        return parse(raw(key, placeholders));
    }

    public Component message(String key) {
        return message(key, null);
    }

    /**
     * 取消息模板并把 {componentKey} 直接替换为给定 Component。
     * 避免"渲染成 Component → 序列化回字符串 → 再反序列化"的往返损耗。
     */
    public Component message(String key, Map<String, String> placeholders,
                            String componentKey, Component component) {
        String raw = raw(key, placeholders);
        String token = "{" + componentKey + "}";
        int idx = raw.indexOf(token);
        if (idx < 0) {
            return message(key, placeholders);
        }
        Component head = parse(raw.substring(0, idx));
        Component tail = parse(raw.substring(idx + token.length()));
        return Component.join(JoinConfiguration.noSeparators(), head, component, tail);
    }

    /**
     * 解析 MiniMessage 并按最终文本缓存结果。冷却时长、页码信息等文本在多次菜单渲染间会重复出现，
     * 缓存可免去重复解析。键为完整替换后的文本，命中即等价，故缓存不会因 reload 产生陈旧渲染。
     */
    private Component parse(String raw) {
        Component cached = renderCache.get(raw);
        if (cached != null) {
            return cached;
        }
        Component parsed = mm.deserialize(raw);
        if (renderCache.size() < RENDER_CACHE_MAX) {
            renderCache.put(raw, parsed);
        }
        return parsed;
    }

    /** 替换占位符与 &lt;prefix&gt; 后的原始模板串。 */
    private String raw(String key, Map<String, String> placeholders) {
        Map<String, String> messages = snapshot.messages();
        String raw = messages.get(key);
        if (raw == null) {
            // 缺失消息本身也必须可配置（默认英文兜底，仅在 error.missing-message 也被删除时才用到）
            raw = messages.getOrDefault("error.missing-message", "<red>Missing message: {key}");
            Map<String, String> merged = new HashMap<>();
            if (placeholders != null) {
                merged.putAll(placeholders);
            }
            merged.put("key", key);
            placeholders = merged;
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return applyPrefix(raw, messages);
    }

    /** 展开 &lt;prefix&gt; 标签（先于 MiniMessage 解析，使前缀中的样式生效）；无该标签时零开销返回。 */
    private static String applyPrefix(String raw, Map<String, String> messages) {
        if (raw.indexOf("<prefix>") < 0) {
            return raw;
        }
        return raw.replace("<prefix>", messages.getOrDefault("prefix", ""));
    }

    /** 将毫秒时长渲染为可配置模板的 Component（天/时/分/秒，单位模板见 messages.yml time.*）。 */
    public Component duration(long millis) {
        if (millis <= 0) {
            return message("time.zero");
        }
        long s = millis / 1000;
        long days = s / 86400;
        long hours = (s % 86400) / 3600;
        long minutes = (s % 3600) / 60;
        long seconds = s % 60;
        List<Component> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(message("time.day", Map.of("value", String.valueOf(days))));
        }
        if (hours > 0) {
            parts.add(message("time.hour", Map.of("value", String.valueOf(hours))));
        }
        if (minutes > 0) {
            parts.add(message("time.minute", Map.of("value", String.valueOf(minutes))));
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add(message("time.second", Map.of("value", String.valueOf(seconds))));
        }
        return Component.join(JoinConfiguration.noSeparators(), parts);
    }

    /** 渲染奖励预览（模板见 messages.yml reward.*，按 Reward 的 previewKey 取模板）。 */
    public Component rewardPreview(Reward reward) {
        return message("reward." + reward.previewKey(), reward.previewArgs());
    }

    /** 将 Component 渲染为纯文本（剥离样式），用于消息占位符与配置解析阶段的预计算。 */
    public static String plain(Component component) {
        return TAG_PATTERN.matcher(MiniMessage.miniMessage().serialize(component)).replaceAll("");
    }

    /** 周期名纯文本（预计算）。 */
    public String plainPeriodName(PeriodType type) {
        return snapshot.periodNames().getOrDefault(type, type.name());
    }

    public int menuPageSize() {
        return snapshot.menuPageSize();
    }

    public int flushIntervalSeconds() {
        return snapshot.flushIntervalSeconds();
    }

    /** 同一玩家的两次菜单点击最小间隔（毫秒），0 表示不限制。 */
    public int clickThrottleMillis() {
        return snapshot.clickThrottleMillis();
    }

    private void saveDefault(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            plugin.saveResource(name, false);
        }
    }
}
