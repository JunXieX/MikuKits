package dev.junxiex.mikukits.kit.reward;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * 物品奖励。ItemStack 在配置加载时（区域线程）已反序列化，发放时直接加入背包。
 * 背包满时溢出物掉落在玩家脚下。
 * <p>
 * 预览文本在构造时一次性计算（只依赖构造后不再变化的物品定义），
 * 避免每次菜单渲染都重新序列化显示名 / 取翻译键。
 */
public final class ItemReward implements Reward {

    private final ItemStack item;
    private final Map<String, String> previewArgs;

    public ItemReward(ItemStack item) {
        this.item = item;
        this.previewArgs = buildPreviewArgs(item);
    }

    @Override
    public int grant(Player player, Plugin plugin) {
        // 复制一份，避免多次领取共享同一实例
        ItemStack copy = item.clone();
        // addItem 会尽力塞入背包，塞不下的部分作为返回值；这部分掉在玩家脚下（不丢失，但会消失且他人可拾取）
        int overflowed = 0;
        for (ItemStack left : player.getInventory().addItem(copy).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
            overflowed += left.getAmount();
        }
        return overflowed;
    }

    @Override
    public String previewKey() {
        return "item";
    }

    @Override
    public Map<String, String> previewArgs() {
        return previewArgs;
    }

    private static Map<String, String> buildPreviewArgs(ItemStack item) {
        // 有自定义显示名则保留其 MiniMessage 形式；否则用原版翻译键，由客户端按玩家语言渲染（中文客户端→中文名）
        String name;
        ItemMeta meta = item.getItemMeta();
        Component dn = meta == null ? null : meta.displayName();
        if (dn != null) {
            name = MiniMessage.miniMessage().serialize(dn);
        } else {
            // 非物品类 Material（如空气/纯方块状态）没有翻译键，回退到枚举名，避免渲染成空白
            String key = item.getType().isItem() ? item.getType().getItemTranslationKey() : null;
            name = (key == null || key.isBlank()) ? item.getType().name() : "<lang:" + key + ">";
        }
        return Map.of(
                "amount", String.valueOf(item.getAmount()),
                "name", name);
    }
}
