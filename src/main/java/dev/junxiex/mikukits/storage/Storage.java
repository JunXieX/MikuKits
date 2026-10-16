package dev.junxiex.mikukits.storage;

import dev.junxiex.mikukits.data.PlayerKitData;

import java.util.UUID;

/**
 * 玩家数据存储接口。
 * <p>
 * 各方法的线程约束由调用方保证：{@code load}/{@code saveBatch} 只在异步线程调用；
 * {@code close} 在关服时由主线程调用。实现必须自行串行化，不得依赖"调用者总是同一线程"。
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
