package dev.junxiex.mikukits.kit.reward;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * 奖励抽象。实现类为不可变值对象，发放时由 RewardDispatcher 在正确的线程调用。
 */
public sealed interface Reward
        permits ItemReward, ConsoleCommandReward, PlayerCommandReward, MoneyReward {

    /**
     * 发放奖励。
     *
     * @param player 目标玩家（已处于其区域/实体线程）
     * @param plugin 插件实例（用于调度）
     * @return 因背包空间不足而溢出到地面的物品总数（非物品类奖励恒返回 0），
     *         供调用方统一提示玩家，避免多件溢出时重复刷屏
     */
    int grant(Player player, Plugin plugin);

    /** 预览模板 key（对应 messages.yml 中 reward.&lt;key&gt;）。 */
    String previewKey();

    /** 预览模板的占位符参数。 */
    Map<String, String> previewArgs();
}
