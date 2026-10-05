package dev.junxiex.mikukits.kit;

import dev.junxiex.mikukits.kit.reward.Reward;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变的礼包定义。配置加载时构建，运行期只读（KitManager 用不可变 Map 持有）。
 */
public final class Kit {

    private final String id;
    private final Component displayName;
    private final String plainName;
    private final ItemStack icon;
    private final String permission;
    private final PermissionDefault permissionDefault;
    private final boolean autoClaim;
    private final long cooldownMillis;
    private final int maxClaims; // <=0 表示无限
    private final Map<PeriodType, Integer> periodLimits; // 值 <=0 表示该周期不限制
    private final List<Reward> rewards;
    private final int sortOrder;

    public Kit(String id, Component displayName, String plainName, ItemStack icon,
               String permission, PermissionDefault permissionDefault, boolean autoClaim,
               long cooldownMillis, int maxClaims, Map<PeriodType, Integer> periodLimits,
               List<Reward> rewards, int sortOrder) {
        this.id = id;
        this.displayName = displayName;
        this.plainName = plainName;
        this.icon = icon;
        this.permission = permission;
        this.permissionDefault = permissionDefault;
        this.autoClaim = autoClaim;
        this.cooldownMillis = cooldownMillis;
        this.maxClaims = maxClaims;
        // 用 EnumMap 包装以保留周期枚举顺序（Map.copyOf 的迭代顺序未定义，会导致 tooltip 顺序漂移）
        this.periodLimits = Collections.unmodifiableMap(new EnumMap<>(periodLimits));
        this.rewards = List.copyOf(rewards);
        this.sortOrder = sortOrder;
    }

    public String id() {
        return id;
    }

    public Component displayName() {
        return displayName;
    }

    /** 显示名的纯文本形式（加载时预计算，避免菜单渲染反复序列化 + 正则剥离）。 */
    public String plainName() {
        return plainName;
    }

    /** 返回图标副本，避免调用方改动共享实例污染其他菜单。 */
    public ItemStack icon() {
        return icon.clone();
    }

    public String permission() {
        return permission;
    }

    /** 该礼包权限节点的默认值（未在权限插件中显式设置时生效）。 */
    public PermissionDefault permissionDefault() {
        return permissionDefault;
    }

    public boolean autoClaim() {
        return autoClaim;
    }

    public long cooldownMillis() {
        return cooldownMillis;
    }

    public int maxClaims() {
        return maxClaims;
    }

    public Map<PeriodType, Integer> periodLimits() {
        return periodLimits;
    }

    public List<Reward> rewards() {
        return rewards;
    }

    public int sortOrder() {
        return sortOrder;
    }
}
