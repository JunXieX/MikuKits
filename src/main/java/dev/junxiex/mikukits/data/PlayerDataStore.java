package dev.junxiex.mikukits.data;

import dev.junxiex.mikukits.storage.Storage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 玩家数据内存缓存 + write-behind 写回队列。
 * <p>
 * 缓存用 {@link ConcurrentHashMap}。单个玩家的 {@link KitRecord} 只在拥有该玩家的
 * 区域/实体线程修改；修改后由该线程生成不可变 {@link Storage.KitRow} 快照入队，
 * 异步线程仅从队列 drain 纯数据行写库，全程不触碰 Bukkit API，也无需跨线程读内部 Map。
 */
public final class PlayerDataStore {

    /**
     * 串行化"出队+写库"与"加载"：
     * 1) 防止两个 flush 线程拿到同一 (uuid,kit) 的新旧快照后乱序写库（旧覆盖新）；
     * 2) 玩家快速重连时，join 的 load 先 flush 掉上一会话的待写行，保证读到最新数据。
     */
    private final Object ioLock = new Object();

    private final Map<UUID, PlayerKitData> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Storage.KitRow> pending = new ConcurrentLinkedDeque<>();
    private final Storage storage;

    public PlayerDataStore(Storage storage) {
        this.storage = storage;
    }

    public PlayerKitData get(UUID uuid) {
        return cache.get(uuid);
    }

    /** 放入缓存（异步加载完成、回投玩家线程后调用）。 */
    public void put(PlayerKitData data) {
        cache.put(data.uuid(), data);
    }

    /** 移除缓存（玩家离线且快照已入队后调用）。 */
    public void remove(UUID uuid) {
        cache.remove(uuid);
    }

    /** 异步线程直接加载（阻塞 IO）。加载前先把该玩家的待写行落库，避免读到旧数据。 */
    public PlayerKitData loadFromStorage(UUID uuid) {
        synchronized (ioLock) {
            // 只写回该玩家的待写行：保证其读到最新数据，同时不被其他玩家的积压拖慢进服加载
            flushPlayerLocked(uuid);
            return storage.load(uuid);
        }
    }

    /** 玩家线程：将某玩家某礼包的当前状态快照入队。 */
    public void enqueue(PlayerKitData data, String kitId) {
        KitRecord rec = data.existingRecord(kitId);
        if (rec != null) {
            pending.add(rec.snapshot(data.uuid(), kitId));
        }
    }

    /** 玩家线程：将某玩家全部记录快照入队（离线时调用）。 */
    public void enqueueAll(PlayerKitData data) {
        for (String kitId : data.records().keySet()) {
            enqueue(data, kitId);
        }
    }

    /**
     * 异步线程：drain 队列并批量写库。
     * 同一 (uuid,kit) 可能有多条快照，按入队顺序（FIFO）写，UPSERT 保证最终一致。
     */
    public void drainAndFlush() {
        synchronized (ioLock) {
            flushPendingLocked();
        }
    }

    /** 调用方必须持有 ioLock。drain 整个队列并写库。 */
    private void flushPendingLocked() {
        if (pending.isEmpty()) {
            return;
        }
        List<Storage.KitRow> batch = new ArrayList<>();
        Storage.KitRow row;
        while ((row = pending.pollFirst()) != null) {
            batch.add(row);
        }
        writeBatchLocked(batch);
    }

    /** 调用方必须持有 ioLock。只 drain 指定玩家的待写行，用于该玩家加载前的定点落库。 */
    private void flushPlayerLocked(UUID uuid) {
        List<Storage.KitRow> batch = new ArrayList<>();
        // ConcurrentLinkedDeque 的迭代器弱一致且支持 remove()，可在不阻塞入队的前提下摘除目标行
        for (Iterator<Storage.KitRow> it = pending.iterator(); it.hasNext(); ) {
            Storage.KitRow row = it.next();
            if (row.uuid().equals(uuid)) {
                it.remove();
                batch.add(row);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        writeBatchLocked(batch);
    }

    /** 调用方必须持有 ioLock。写库失败时把已出队的行放回队首，避免丢数据且保持 FIFO。 */
    private void writeBatchLocked(List<Storage.KitRow> batch) {
        try {
            storage.saveBatch(batch);
        } catch (RuntimeException ex) {
            // 必须放回队首（逆序 addFirst 还原原顺序）：若追加到队尾，写库失败期间新入队的
            // 更新快照会排在旧快照之前，下一轮 flush 会把旧数据写在后面，导致领取次数/冷却倒退。
            for (int i = batch.size() - 1; i >= 0; i--) {
                pending.addFirst(batch.get(i));
            }
            throw ex;
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    public int cachedCount() {
        return cache.size();
    }

    public void close() {
        storage.close();
    }
}
