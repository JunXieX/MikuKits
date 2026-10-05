package dev.junxiex.mikukits;

import dev.junxiex.mikukits.command.CommandService;
import dev.junxiex.mikukits.config.ConfigManager;
import dev.junxiex.mikukits.data.LimitService;
import dev.junxiex.mikukits.data.PlayerDataStore;
import dev.junxiex.mikukits.dialog.DialogMenuService;
import dev.junxiex.mikukits.kit.Kit;
import dev.junxiex.mikukits.kit.KitManager;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        } catch (Exception ex) {
            getLogger().warning("关闭时写回失败: " + ex.getMessage());
        }
        dataStore.close();
    }

    /** 重新加载配置与礼包表。礼包表立即（原子）生效；权限表与调度器属全局状态，必要时回到全局区域线程。 */
    public void reloadConfigs() {
        Map<String, Kit> kits = loadKits();
        // Folia 下玩家执行 /kits reload 时当前是区域线程，而 addPermission/removePermission
        // 会遍历并重算所有在线玩家权限，属全局状态修改，必须回到全局区域线程
        if (Bukkit.isGlobalTickThread()) {
            applyGlobalState(kits);
        } else {
            getServer().getGlobalRegionScheduler().run(this, task -> applyGlobalState(kits));
        }
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
        Set<String> current = new HashSet<>();
        for (Kit kit : kits.values()) {
            String node = kit.permission();
            current.add(node);
            Permission existing = pm.getPermission(node);
            if (existing != null) {
                if (existing.getDefault() == kit.permissionDefault()) {
                    continue;
                }
                // permission-default 被改过：重新注册才能让新默认值生效
                pm.removePermission(node);
            }
            try {
                pm.addPermission(new Permission(node, "领取礼包 " + kit.id(), kit.permissionDefault()));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("礼包 " + kit.id() + " 的权限节点 '" + node + "' 非法，已忽略");
            }
        }
        // 清理已删除礼包遗留的权限节点，避免其默认值长期残留
        registeredKitPermissions.removeIf(node -> {
            if (current.contains(node)) {
                return false;
            }
            pm.removePermission(node);
            return true;
        });
        registeredKitPermissions.addAll(current);
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
