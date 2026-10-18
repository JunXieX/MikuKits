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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    /** 写回间隔上限：避免配置成极大值后，内存中长时间积压未落库的领取记录。 */
    private static final int FLUSH_SECONDS_MAX = 600;
    private static final int DEFAULT_PAGE_SIZE = 8;
    private static final int DEFAULT_FLUSH_SECONDS = 30;
    private static final int DEFAULT_THROTTLE_MS = 200;
    /** 点击节流上限：再大就会让正常操作明显卡顿，且现象只是"点了没反应"，难以自查。 */
    private static final int THROTTLE_MS_MAX = 5000;
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

    /** 重新加载全部配置，返回解析出的礼包表。所有内容解析完成后才发布新快照。 */
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
        int rawFlush = config.getInt("storage.flush-interval-seconds", DEFAULT_FLUSH_SECONDS);
        if (rawFlush > FLUSH_SECONDS_MAX) {
            // 写回间隔过长意味着内存里会积压很久未落库的领取记录，一旦进程异常退出就全丢
            plugin.getLogger().warning("config.yml 的 storage.flush-interval-seconds=" + rawFlush
                    + " 过大，已钳制为 " + FLUSH_SECONDS_MAX + " 秒");
        }
        int flushIntervalSeconds = Math.max(FLUSH_SECONDS_MIN, Math.min(FLUSH_SECONDS_MAX, rawFlush));
        int rawThrottle = config.getInt("menu.click-throttle-ms", DEFAULT_THROTTLE_MS);
        if (rawThrottle > THROTTLE_MS_MAX) {
            // 填成很大的值（如 60000）会让玩家几乎点不动菜单，且现象是"点了没反应"，很难自查
            plugin.getLogger().warning("config.yml 的 menu.click-throttle-ms=" + rawThrottle
                    + " 过大，已钳制为 " + THROTTLE_MS_MAX + " 毫秒");
        }
        int clickThrottleMillis = Math.max(0, Math.min(THROTTLE_MS_MAX, rawThrottle));

        Map<String, String> newMessages = new HashMap<>();
        // 先铺一层 JAR 内置默认值：老服务器的 messages.yml 不会随版本升级自动补键，
        // 缺了新增 key 就会渲染成"缺少消息"。内置默认值兜底后，新增文案对老存档同样即时可用，
        // 同时不覆盖玩家已有的任何自定义内容。
        Map<String, String> bundled = loadBundledMessages();
        newMessages.putAll(bundled);
        YamlConfiguration msg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        for (String key : msg.getKeys(true)) {
            String value = msg.getString(key);
            if (value != null) {
                newMessages.put(key, value);
            }
        }
        warnMissingMessageKeys(msg, bundled.keySet());
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

        Map<String, Kit> kits = new HashMap<>();
        File[] files = kitsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName().substring(0, f.getName().length() - 4);
                try {
                    YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                    Kit kit = parser.parse(name, yml);
                    Kit previous = kits.put(kit.id(), kit);
                    if (previous != null) {
                        // 文件名大小写不同会归一为同一 id，后解析者静默覆盖前者，属难以察觉的配置冲突
                        plugin.getLogger().warning("礼包 id 冲突: '" + f.getName()
                                + "' 与另一文件解析为同一 id '" + kit.id() + "'，仅其一会生效");
                    }
                } catch (Exception ex) {
                    plugin.getLogger().severe("解析礼包 " + f.getName() + " 失败: " + ex.getMessage());
                }
            }
        }

        // 全部内容解析成功后才发布快照：否则一旦后续解析抛错，会出现"消息已是新的、礼包还是旧的"
        // 这种半成品状态，与 reload 失败时"保留原有配置"的承诺不符。
        // 旧渲染缓存一并作废（键虽等价安全，但清掉以免长期占用）。
        renderCache.clear();
        snapshot = new Snapshot(messages, Map.copyOf(constants), Map.copyOf(periodNames),
                menuPageSize, flushIntervalSeconds, clickThrottleMillis);

        return kits;
    }

    /**
     * 列出磁盘 messages.yml 相对内置默认值缺失的键并告警。
     * <p>
     * 这些键会回退到内置默认值（不会显示成"缺少消息"），但玩家可能是在升级后漏了新增项、
     * 或误删了自己想改的条目，必须让服主知道，而不是静默生效。
     */
    private void warnMissingMessageKeys(YamlConfiguration msg, Set<String> bundledKeys) {
        Set<String> missing = new TreeSet<>();
        for (String key : bundledKeys) {
            if (!msg.isSet(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("messages.yml 缺少 " + missing.size()
                    + " 个键，已使用内置默认文案: " + String.join(", ", missing));
        }
    }

    /** 读取 JAR 内置的 messages.yml 作为缺失键的兜底（仅默认值，不写回磁盘）。 */
    private Map<String, String> loadBundledMessages() {
        Map<String, String> defaults = new HashMap<>();
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in == null) {
                return defaults;
            }
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String key : bundled.getKeys(true)) {
                String value = bundled.getString(key);
                if (value != null) {
                    defaults.put(key, value);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // 兜底失败不影响主流程：仍以磁盘 messages.yml 为准
            plugin.getLogger().warning("读取内置默认消息失败: " + ex.getMessage());
        }
        return defaults;
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
            // 模板里没有该占位符：直接解析已算好的 raw，避免再走一次 raw 的完整替换流程
            return parse(raw);
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
        // 向上取整：剩余 400ms 时仍在冷却中，若按整除会显示"0秒"，让玩家误以为已可领取
        long s = (millis + 999L) / 1000L;
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
