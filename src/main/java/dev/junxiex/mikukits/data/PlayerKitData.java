package dev.junxiex.mikukits.data;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家的全部礼包领取记录缓存。
 * <p>
 * 记录的读写约定在拥有该玩家的线程（玩家/区域线程）上进行；容器本身用
 * {@link ConcurrentHashMap} 兜底，避免未来线程归属变化时（如 quit 与交互落在不同线程）
 * 出现结构性并发损坏。{@link KitRecord} 内部仍按单线程约定访问。
 */
public final class PlayerKitData {

    private final UUID uuid;
    private final Map<String, KitRecord> records = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public PlayerKitData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public boolean loaded() {
        return loaded;
    }

    public void markLoaded() {
        this.loaded = true;
    }

    /** 获取记录，不存在则创建（不标脏）。 */
    public KitRecord record(String kitId) {
        return records.computeIfAbsent(kitId, k -> new KitRecord());
    }

    public KitRecord existingRecord(String kitId) {
        return records.get(kitId);
    }

    /** 只读视图，防止调用方绕过 {@link #record(String)} 直接改动内部 Map。 */
    public Map<String, KitRecord> records() {
        return Collections.unmodifiableMap(records);
    }
}
