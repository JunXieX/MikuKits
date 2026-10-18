package dev.junxiex.mikukits;

import dev.junxiex.mikukits.command.CommandService;
import dev.junxiex.mikukits.config.ConfigManager;
import dev.junxiex.mikukits.data.LimitService;
import dev.junxiex.mikukits.data.PlayerDataStore;
import dev.junxiex.mikukits.dialog.DialogMenuService;
import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.kit.KitManager;
import dev.junxiex.mikukits.kit.reward.MoneyReward;
import dev.junxiex.mikukits.listener.CustomClickListener;
import dev.junxiex.mikukits.listener.PlayerConnectionListener;
import dev.junxiex.mikukits.reward.RewardDispatcher;
import dev.junxiex.mikukits.storage.SqliteStorage;
import dev.junxiex.mikukits.storage.Storage;
import dev.junxiex.mikukits.util.Permissions;
import dev.junxiex.mikukits.vault.VaultHook;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * MikuKits 主类。兼容 Paper 与 Folia（单代码库，全部使用区域/异步调度器）。
 */
public final class MikuKitsPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private KitManager kitManager;
    private LimitService limitService;
    private PlayerDataStore dataStore;
    private VaultHook vaultHook;
    private RewardDispatcher dispatcher;
    private DialogMenuService menuService;

    private volatile ScheduledTask flushTask;
    private volatile int scheduledFlushSeconds;
    /** 上一次注册的礼包权限节点，reload 时用于清理已被删除礼包遗留的陈旧节点（仅在全局区域线程访问）。 */
    private final Set<String> registeredKitPermissions = new HashSet<>();

    @Override
    public void onEnable() {
        boolean folia = ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
        getLogger().info("运行于 " + (folia ? "Folia" : "Paper") + "，Minecraft "
                + ServerBuildInfo.buildInfo().minecraftVersionName());

        registerBasePermissions();

        configManager = new ConfigManager(this);
        kitManager = new KitManager();
        limitService = new LimitService();
        vaultHook = new VaultHook(this);

        Storage storage;
        try {
            // 驱动 jar 由 MikuKitsLoader 注入插件 classpath；DriverManager 的 ServiceLoader
            // 自动发现使用系统 classloader，看不到插件类路径，必须用插件 classloader 显式加载
            Class.forName("org.sqlite.JDBC", true, getClassLoader());
            storage = new SqliteStorage(new File(getDataFolder(), "data.db"));
        } catch (ClassNotFoundException | SQLException e) {
            getLogger().severe("无法初始化 SQLite 存储（驱动缺失或数据库损坏），插件禁用: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataStore = new PlayerDataStore(storage);

        dispatcher = new RewardDispatcher(this);
        menuService = new DialogMenuService(this);
        CommandService commandService = new CommandService(this);

        // onEnable 本身就在全局/主线程，直接应用，不依赖调度器（避免启动期投递失败导致权限未注册）
        applyGlobalState(loadKits());

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CustomClickListener(this), this);
        commandService.register();

        getLogger().info("已加载 " + kitManager.size() + " 个礼包");
    }

    /**
     * 启动期检查"配了金钱奖励但没有 Vault"的情况。
     * <p>
     * 1.4.1 起金钱发放失败会被正确计为失败（不消耗次数），但若服主漏装 Vault，
     * 该礼包的每次领取都会失败、玩家反复看到"发放失败"，很难自行定位。
     * 这里在启动时就点明原因，避免上线后才发现。
     */
    private void warnUnusableMoneyRewards() {
        if (vaultHook.available()) {
            return;
        }
        for (Kit kit : kitManager.all()) {
            boolean hasMoney = kit.rewards().stream()
                    .anyMatch(r -> r instanceof MoneyReward);
            if (hasMoney) {
                getLogger().warning("礼包 " + kit.id()
                        + " 配置了 money 奖励，但服务器未安装 Vault（或没有 Economy 提供者），"
                        + "该礼包的领取将始终失败。请安装 Vault + 经济插件，或移除这项奖励。");
            }
        }
    }

    @Override
    public void onDisable() {
        // 存储初始化失败时 dataStore 为 null，需判空避免 NPE
        if (dataStore == null) {
            return;
        }
        // 每次领取/离线都已在发生时即时入队，故关闭只需：停掉异步任务 → drain 队列 → 关连接。
        // 不再遍历在线玩家读取 KitRecord —— 那些记录归属各自区域线程，主线程读取是数据竞争。
        getServer().getAsyncScheduler().cancelTasks(this);
        try {
            dataStore.drainAndFlush();
            int left = dataStore.pendingCount();
            if (left > 0) {
                // drainAndFlush 失败时会把整批行放回队列，此处必须显式告警：这些数据将随连接关闭丢失
                getLogger().severe("关闭时仍有 " + left + " 条领取数据未能写回数据库，这些数据将丢失");
            }
        } catch (Exception ex) {
            getLogger().warning("关闭时写回失败: " + ex.getMessage());
        }
        dataStore.close();
    }

    /**
     * 重新加载配置与礼包表。礼包表立即（原子）生效；权限表与调度器属全局状态，必要时回到全局区域线程。
     * <p>
     * 配置解析含文件 IO 与 ItemStack 反序列化。Folia 下玩家执行 /kits reload 时正处于其区域 tick
     * 线程，若在此直接做文件 IO 会拖慢该区域内所有实体；而 ItemStack 反序列化又要求 tick 线程，
     * 故整体投递到全局区域线程执行（该线程同样是 tick 线程，且不阻塞任何玩家区域）。
     *
     * @param onDone 重载结束后的回调，入参为是否成功，同样在全局区域线程执行，供指令执行者反馈
     */
    public void reloadConfigs(Consumer<Boolean> onDone) {
        if (Bukkit.isGlobalTickThread()) {
            applyReload(onDone);
        } else {
            getServer().getGlobalRegionScheduler().run(this, task -> applyReload(onDone));
        }
    }

    /** 在全局区域线程执行实际重载。解析失败时保留原有配置，避免半成品配置污染运行期状态。 */
    private void applyReload(Consumer<Boolean> onDone) {
        Map<String, Kit> kits;
        try {
            kits = loadKits();
        } catch (Exception ex) {
            getLogger().severe("重新加载配置失败，已保留原有配置: " + ex.getMessage());
            onDone.accept(false);
            return;
        }
        applyGlobalState(kits);
        onDone.accept(true);
    }

    /** 解析配置并原子替换礼包表，返回新表。可在任意线程调用。 */
    private Map<String, Kit> loadKits() {
        Map<String, Kit> kits = configManager.loadAll();
        kitManager.replaceAll(kits);
        return kits;
    }

    private void applyGlobalState(Map<String, Kit> kits) {
        registerKitPermissions(kits);
        scheduleFlush();
        warnUnusableMoneyRewards();
    }

    /**
     * 运行时注册基础权限节点。paper-plugin.yml 的 permissions 字段格式（List&lt;Permission&gt;）
     * 与 plugin.yml 不同且文档不明确，故统一在启用时注册，保证权限描述与默认值生效。
     */
    private void registerBasePermissions() {
        PluginManager pm = getServer().getPluginManager();
        registerPermission(pm, Permissions.WILDCARD, "领取所有礼包", PermissionDefault.OP);
        registerPermission(pm, Permissions.BYPASS_COOLDOWN, "绕过冷却时间限制", PermissionDefault.OP);
        registerPermission(pm, Permissions.BYPASS_LIMIT, "绕过次数限制", PermissionDefault.OP);
        if (pm.getPermission(Permissions.ADMIN) == null) {
            Permission admin = new Permission(Permissions.ADMIN, "插件管理权限", PermissionDefault.OP);
            admin.getChildren().put(Permissions.WILDCARD, true);
            admin.getChildren().put(Permissions.BYPASS_COOLDOWN, true);
            admin.getChildren().put(Permissions.BYPASS_LIMIT, true);
            pm.addPermission(admin);
        }
    }

    /**
     * 为每个礼包注册其权限节点，默认值取自 kits/*.yml 的 permission-default。
     * 不注册的话，未在权限插件中出现的节点会回退到 OP 判定，导致普通玩家开箱即用为空菜单。
     */
    private void registerKitPermissions(Map<String, Kit> kits) {
        PluginManager pm = getServer().getPluginManager();
        Map<String, PermissionDefault> nodeDefaults = new HashMap<>();
        for (Kit kit : kits.values()) {
            String node = kit.permission();
            PermissionDefault previousDefault = nodeDefaults.putIfAbsent(node, kit.permissionDefault());
            if (previousDefault != null && previousDefault != kit.permissionDefault()) {
                // 多个礼包复用同一权限节点却声明了不同默认值：最终只有一个默认值生效，
                // 且取决于解析顺序，属难以排查的配置冲突，必须显式告警
                getLogger().warning("权限节点 '" + node + "' 被多个礼包复用且 permission-default 不一致（"
                        + previousDefault + " / " + kit.permissionDefault() + "），仅其中一个会生效");
                continue;
            }
            Permission existing = pm.getPermission(node);
            if (existing == null) {
                try {
                    pm.addPermission(new Permission(node, "领取礼包 " + kit.id(), kit.permissionDefault()));
                    // 只有真正注册成功才记为"本插件拥有"，后续清理才不会误删他人节点
                    registeredKitPermissions.add(node);
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("礼包 " + kit.id() + " 的权限节点 '" + node + "' 非法，已忽略");
                }
                continue;
            }
            if (existing.getDefault() == kit.permissionDefault()) {
                // 已存在且默认值一致：可能本次是重载、也可能节点本来就是别人的。
                // 只有原本就登记在本插件名下时才保持所有权，不能在这里无条件认领他人的节点
                continue;
            }
            if (!registeredKitPermissions.contains(node)) {
                // 同名节点由其他插件（或其 plugin.yml）注册，本插件从未拥有它。
                // 强行改写或删除都会影响别的插件，这里只提示冲突，交由服主在权限插件中显式设置。
                getLogger().warning("权限节点 '" + node + "' 已由其他插件注册且默认值不同（已有 "
                        + existing.getDefault() + "，礼包声明 " + kit.permissionDefault()
                        + "），保留已有注册；如需生效请在权限插件中显式设置");
                continue;
            }
            // 本插件自己注册过、且 permission-default 被改过：就地改默认值，避免删了再加导致节点短暂消失
            existing.setDefault(kit.permissionDefault());
        }
        // 清理已删除礼包遗留的权限节点：registeredKitPermissions 只含本插件注册成功的节点
        Set<String> desired = new HashSet<>();
        for (Kit kit : kits.values()) {
            desired.add(kit.permission());
        }
        registeredKitPermissions.removeIf(node -> {
            if (desired.contains(node)) {
                return false;
            }
            pm.removePermission(node);
            return true;
        });
    }

    private void registerPermission(PluginManager pm, String name, String description, PermissionDefault def) {
        if (pm.getPermission(name) == null) {
            pm.addPermission(new Permission(name, description, def));
        }
    }

    /** 定时批量写回（异步线程，仅 drain 纯数据队列）。周期随 reload 生效。 */
    private synchronized void scheduleFlush() {
        int interval = configManager.flushIntervalSeconds();
        if (flushTask != null && !flushTask.isCancelled() && interval == scheduledFlushSeconds) {
            return;
        }
        if (flushTask != null) {
            flushTask.cancel();
        }
        scheduledFlushSeconds = interval;
        flushTask = getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            try {
                dataStore.drainAndFlush();
            } catch (Exception ex) {
                // 关服时连接可能已被 close，属预期情况，不再误报
                if (!getServer().isStopping()) {
                    getLogger().warning("写回数据失败: " + ex.getMessage());
                }
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public KitManager kitManager() {
        return kitManager;
    }

    public LimitService limitService() {
        return limitService;
    }

    public PlayerDataStore dataStore() {
        return dataStore;
    }

    public VaultHook vaultHook() {
        return vaultHook;
    }

    public RewardDispatcher dispatcher() {
        return dispatcher;
    }

    public DialogMenuService menuService() {
        return menuService;
    }
}
