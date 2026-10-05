package dev.junxiex.mikukits.kit;

import dev.junxiex.mikukits.util.Permissions;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 礼包注册表。用 AtomicReference 持有不可变 Map，reload 时整体替换，读取无锁。
 */
public final class KitManager {

    private final AtomicReference<Map<String, Kit>> kits = new AtomicReference<>(Map.of());

    public void replaceAll(Map<String, Kit> newKits) {
        Map<String, Kit> sorted = new LinkedHashMap<>();
        newKits.values().stream()
                .sorted(Comparator.comparingInt(Kit::sortOrder).thenComparing(Kit::id))
                .forEach(k -> sorted.put(k.id(), k));
        kits.set(Collections.unmodifiableMap(sorted));
    }

    public Kit get(String id) {
        return kits.get().get(id);
    }

    /** 全部礼包（按 sort-order 已排序），用于补全等不区分权限的场景。 */
    public List<Kit> all() {
        return new ArrayList<>(kits.get().values());
    }

    /** 玩家有权限看到的礼包（按 sort-order 已排序）。 */
    public List<Kit> visibleTo(Player player) {
        // 通配/管理员判定只算一次，循环内只查各礼包的专属节点，减少权限查询
        boolean canAll = Permissions.canClaimAll(player);
        List<Kit> out = new ArrayList<>();
        for (Kit k : kits.get().values()) {
            if (Permissions.canClaim(player, k.permission(), canAll)) {
                out.add(k);
            }
        }
        return out;
    }

    public List<Kit> autoClaimKits() {
        List<Kit> out = new ArrayList<>();
        for (Kit k : kits.get().values()) {
            if (k.autoClaim()) {
                out.add(k);
            }
        }
        return out;
    }

    public int size() {
        return kits.get().size();
    }
}
