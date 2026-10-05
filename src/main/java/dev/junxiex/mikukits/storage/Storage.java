package dev.junxiex.mikukits.storage;

import dev.junxiex.mikukits.data.PlayerKitData;

import java.util.UUID;

/**
 * 玩家数据存储接口。所有方法均为阻塞 IO，只允许在 AsyncScheduler 线程调用。
 */
public interface Storage {

    /** 加载玩家数据到新的 PlayerKitData（不含 Bukkit API，线程安全）。 */
    PlayerKitData load(UUID uuid);

    /** 批量写回脏记录行。 */
    void saveBatch(Iterable<KitRow> rows);

    void close();

    /** 一行待写回的数据（纯字符串/数值快照，在玩家线程构建）。 */
    record KitRow(UUID uuid, String kitId, long lastClaimAt, int totalClaims, String periodData) {
    }
}
