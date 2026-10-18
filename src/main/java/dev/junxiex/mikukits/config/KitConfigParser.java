package dev.junxiex.mikukits.config;

import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.kit.PeriodType;
import dev.junxiex.mikukits.kit.reward.ConsoleCommandReward;
import dev.junxiex.mikukits.kit.reward.ItemReward;
import dev.junxiex.mikukits.kit.reward.MoneyReward;
import dev.junxiex.mikukits.kit.reward.PlayerCommandReward;
import dev.junxiex.mikukits.kit.reward.Reward;
import dev.junxiex.mikukits.util.Permissions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 将 kits/*.yml 解析为不可变 Kit 模型。
 * 必须在区域线程调用（涉及 ItemStack 反序列化）。
 * <p>
 * 所有被跳过的奖励项都会打印警告，避免配置写错后静默失效、难以排查。
 */
public final class KitConfigParser {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Logger logger;

    public KitConfigParser(Logger logger) {
        this.logger = logger;
    }

    public Kit parse(String fileName, ConfigurationSection sec) {
        String id = fileName.toLowerCase(Locale.ROOT);
        // 未配置 display-name 时直接以文件名（即礼包 id）兜底，不再额外注入样式标签
        Component displayName = mm.deserialize(sec.getString("display-name", id));

        String permission = sec.getString("permission", Permissions.KIT_PREFIX + id);
        PermissionDefault permissionDefault = parseDefault(id, sec.getString("permission-default", "true"));

        ItemStack icon = readIcon(id, sec);
        boolean autoClaim = sec.getBoolean("auto-claim", false);
        long cooldownMillis = readCooldownMillis(id, sec);
        int maxClaims = readMaxClaims(id, sec);
        int sortOrder = sec.getInt("sort-order", 0);

        Map<PeriodType, Integer> periodLimits = new EnumMap<>(PeriodType.class);
        ConfigurationSection pl = sec.getConfigurationSection("period-limits");
        if (pl != null) {
            for (String key : pl.getKeys(false)) {
                PeriodType type = parsePeriod(key);
                if (type == null) {
                    logger.warning("礼包 " + id + " 的 period-limits." + key
                            + " 不是有效周期（可选 daily/weekly/monthly），已忽略");
                    continue;
                }
                int v = pl.getInt(key, 0);
                if (v > 0) {
                    periodLimits.put(type, v);
                } else {
                    // 0/负数会被判定处跳过（等于该周期不限制）。服主写下这个键显然是想限制，
                    // 结果却是完全不限制，属静默反转，必须告警
                    logger.warning("礼包 " + id + " 的 period-limits." + key + "=" + v
                            + " 非正数，该周期将不做限制；如需限制请填写正整数");
                }
            }
        }

        List<Reward> rewards = new ArrayList<>();
        List<?> rawRewards = sec.getList("rewards");
        if (rawRewards != null) {
            for (Object entry : rawRewards) {
                // 刻意不用 getMapList：它会把非 Map 元素静默丢弃，与"配置错误必须告警"的原则冲突
                if (!(entry instanceof Map<?, ?> map)) {
                    logger.warning("礼包 " + id + " 的 rewards 中存在非结构化奖励项（" + entry + "），已跳过");
                    continue;
                }
                Reward r = parseReward(id, toSection(map));
                if (r != null) {
                    rewards.add(r);
                }
            }
        }
        if (rewards.isEmpty()) {
            logger.warning("礼包 " + id + " 未配置任何有效奖励");
        }
        // auto-claim 搭配"无任何次数限制"会在每次进服重复发放，几乎必然是配置疏漏
        if (autoClaim && maxClaims <= 0 && periodLimits.isEmpty()) {
            logger.warning("礼包 " + id + " 开启了 auto-claim 但未设置次数限制"
                    + "（max-claims 或 period-limits），玩家每次进服都会重复获得，请确认是否符合预期");
        }

        return new Kit(id, displayName, ConfigManager.plain(displayName), icon, permission,
                permissionDefault, autoClaim, cooldownMillis, maxClaims, periodLimits, rewards, sortOrder);
    }

    /**
     * 读取冷却秒数并转为毫秒。
     * <p>
     * 负数在语义上无意义（会变成"永久无冷却"），溢出则会让 cooldownMillis 变成负数或极小值，
     * 使冷却判定彻底失效；两者都必须钳制并告警，否则属于静默的配置失效。
     */
    private long readCooldownMillis(String kitId, ConfigurationSection sec) {
        long seconds = sec.getLong("cooldown-seconds", 0L);
        if (seconds <= 0) {
            if (seconds < 0) {
                logger.warning("礼包 " + kitId + " 的 cooldown-seconds=" + seconds
                        + " 为负数，已按 0（无冷却）处理");
            }
            return 0L;
        }
        if (seconds > Long.MAX_VALUE / 1000L) {
            logger.warning("礼包 " + kitId + " 的 cooldown-seconds=" + seconds
                    + " 过大，已按最大值处理");
            return Long.MAX_VALUE;
        }
        return seconds * 1000L;
    }

    /**
     * 读取总领取次数上限。
     * <p>
     * 配置语义是"0 表示不限"，负数没有定义。而判定处用的是 {@code maxClaims > 0}，
     * 负数会被静默当作"不限次数"——服主本想限制却写错符号，就会得到一个可无限领取的礼包。
     * 这类错误必须显式告警，不能靠"恰好等价于不限"蒙混过去。
     */
    private int readMaxClaims(String kitId, ConfigurationSection sec) {
        int value = sec.getInt("max-claims", 0);
        if (value < 0) {
            logger.warning("礼包 " + kitId + " 的 max-claims=" + value
                    + " 为负数，已按 0（不限次数）处理；如需限制请填写正整数");
            return 0;
        }
        return value;
    }

    private static ConfigurationSection toSection(Map<?, ?> map) {
        MemoryConfiguration mc = new MemoryConfiguration();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key) {
                mc.set(key, e.getValue());
            }
        }
        return mc;
    }

    /** 按名称（忽略大小写）匹配周期类型，未识别返回 null。 */
    private static PeriodType parsePeriod(String key) {
        for (PeriodType type : PeriodType.values()) {
            if (type.name().equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }

    private PermissionDefault parseDefault(String kitId, String raw) {
        PermissionDefault def = PermissionDefault.getByName(raw.toUpperCase(Locale.ROOT));
        if (def == null) {
            logger.warning("礼包 " + kitId + " 的 permission-default='" + raw
                    + "' 无效（可选 true/false/op/not_op），已回退为 true");
            return PermissionDefault.TRUE;
        }
        return def;
    }

    private Reward parseReward(String kitId, ConfigurationSection rs) {
        String type = rs.getString("type", "").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "item" -> {
                ItemStack stack = readItemSection(kitId, rs);
                if (stack == null) {
                    yield null;
                }
                int amount = rs.getInt("amount", stack.getAmount());
                if (amount != stack.getAmount()) {
                    stack.setAmount(Math.max(1, amount));
                }
                yield new ItemReward(stack);
            }
            case "money" -> {
                double amount = rs.getDouble("amount", 0.0);
                if (amount <= 0) {
                    logger.warning("礼包 " + kitId + " 的 money 奖励金额非正数（" + amount + "），已跳过");
                    yield null;
                }
                yield new MoneyReward(amount);
            }
            case "player-command" -> commandReward(kitId, rs, false);
            case "console-command" -> commandReward(kitId, rs, true);
            case "" -> {
                logger.warning("礼包 " + kitId + " 存在缺少 type 字段的奖励项，已跳过");
                yield null;
            }
            default -> {
                logger.warning("礼包 " + kitId + " 存在未知奖励类型 '" + type + "'，已跳过");
                yield null;
            }
        };
    }

    private Reward commandReward(String kitId, ConfigurationSection rs, boolean console) {
        String command = rs.getString("command", "").trim();
        if (command.isEmpty()) {
            logger.warning("礼包 " + kitId + " 的 " + (console ? "console-command" : "player-command")
                    + " 奖励未配置 command，已跳过");
            return null;
        }
        return console ? new ConsoleCommandReward(command) : new PlayerCommandReward(command);
    }

    /** 优先读 base64（item），否则读 material 简写。 */
    private ItemStack readItemSection(String kitId, ConfigurationSection rs) {
        String b64 = rs.getString("item");
        if (b64 != null && !b64.isEmpty()) {
            try {
                ItemStack stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
                // base64 可能序列化的是空气（空栈）：发空气等于什么都没给，属静默失效，必须拦截
                if (stack == null || stack.getType().isAir()) {
                    logger.warning("礼包 " + kitId + " 的物品奖励 item 解析为空气（空物品），已跳过");
                    return null;
                }
                return stack;
            } catch (RuntimeException ex) {
                logger.warning("礼包 " + kitId + " 的物品奖励 base64 无法解析（" + ex.getMessage()
                        + "），尝试回退到 material");
            }
        }
        String mat = rs.getString("material");
        if (mat != null) {
            Material m = Material.matchMaterial(mat);
            // 非物品类 Material（AIR/WATER 等）构造 ItemStack 会抛异常，必须提前拦下
            if (m != null && m.isItem()) {
                return new ItemStack(m);
            }
            logger.warning("礼包 " + kitId + " 的物品奖励 material='" + mat + "' 不是有效物品，已跳过");
            return null;
        }
        logger.warning("礼包 " + kitId + " 的物品奖励既无有效 item 也无 material，已跳过");
        return null;
    }

    /** 图标：优先 base64（icon），否则 icon-material 简写，最后 fallback 到 CHEST。 */
    private ItemStack readIcon(String kitId, ConfigurationSection sec) {
        String b64 = sec.getString("icon");
        if (b64 != null && !b64.isEmpty()) {
            try {
                ItemStack icon = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
                // 空气图标在详情页会渲染成空白，直接降级为 CHEST 更符合预期
                if (icon != null && !icon.getType().isAir()) {
                    return icon;
                }
                logger.warning("礼包 " + kitId + " 的 icon 解析为空气（空物品），已回退为 CHEST");
            } catch (RuntimeException ex) {
                logger.warning("礼包 " + kitId + " 的 icon base64 无法解析（" + ex.getMessage()
                        + "），尝试回退到 icon-material");
            }
        }
        String mat = sec.getString("icon-material");
        if (mat != null) {
            Material m = Material.matchMaterial(mat);
            // 非物品类 Material 会让 new ItemStack 抛异常，进而导致整个礼包解析失败被丢弃；
            // 这里只降级图标，保住该礼包本身
            if (m != null && m.isItem()) {
                return new ItemStack(m);
            }
            logger.warning("礼包 " + kitId + " 的 icon-material='" + mat + "' 不是有效物品，已回退为 CHEST");
        }
        return new ItemStack(Material.CHEST);
    }
}
