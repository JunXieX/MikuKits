package dev.junxiex.mikukits.storage;

import dev.junxiex.mikukits.data.KitRecord;
import dev.junxiex.mikukits.data.PlayerKitData;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * SQLite 存储实现。WAL 模式，单连接。
 * <p>
 * 该连接会被多个异步任务（玩家 join 的并发加载、定时 flush）以及关闭时的主线程
 * 共同访问，而 JDBC {@link Connection} 并非线程安全，因此所有数据库操作都必须在
 * {@link #dbLock} 上串行化。SQLite 本身也会串行化写入，锁竞争开销可忽略。
 */
public final class SqliteStorage implements Storage {

    private final Object dbLock = new Object();
    private final Connection connection;

    public SqliteStorage(File dbFile) throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        synchronized (dbLock) {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS player_kits (
                          uuid TEXT NOT NULL,
                          kit_id TEXT NOT NULL,
                          last_claim_at INTEGER NOT NULL DEFAULT 0,
                          total_claims INTEGER NOT NULL DEFAULT 0,
                          period_data TEXT NOT NULL DEFAULT '',
                          PRIMARY KEY (uuid, kit_id)
                        )""");
            }
        }
    }

    @Override
    public PlayerKitData load(UUID uuid) {
        PlayerKitData data = new PlayerKitData(uuid);
        String sql = "SELECT kit_id, last_claim_at, total_claims, period_data FROM player_kits WHERE uuid = ?";
        synchronized (dbLock) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        KitRecord rec = data.record(rs.getString("kit_id"));
                        rec.setLastClaimAt(rs.getLong("last_claim_at"));
                        rec.loadPeriods(rs.getString("period_data"));
                        rec.loadTotalClaims(rs.getInt("total_claims"));
                    }
                }
            } catch (SQLException e) {
                throw new StorageException("加载玩家数据失败: " + uuid, e);
            }
        }
        data.markLoaded();
        return data;
    }

    @Override
    public void saveBatch(Iterable<KitRow> rows) {
        String sql = """
                INSERT INTO player_kits (uuid, kit_id, last_claim_at, total_claims, period_data)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (uuid, kit_id) DO UPDATE SET
                  last_claim_at = excluded.last_claim_at,
                  total_claims = excluded.total_claims,
                  period_data = excluded.period_data""";
        synchronized (dbLock) {
            try {
                // 连接构造时即为自动提交，且 load/close 都不改动该状态，故此处无需先读取再恢复；
                // 读取时保持自动提交也可避免 SELECT 长期持有 WAL 读事务
                connection.setAutoCommit(false);
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    int n = 0;
                    for (KitRow row : rows) {
                        ps.setString(1, row.uuid().toString());
                        ps.setString(2, row.kitId());
                        ps.setLong(3, row.lastClaimAt());
                        ps.setInt(4, row.totalClaims());
                        ps.setString(5, row.periodData());
                        ps.addBatch();
                        if (++n % 200 == 0) {
                            ps.executeBatch();
                        }
                    }
                    ps.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw new StorageException("批量写回失败", e);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (dbLock) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public static final class StorageException extends RuntimeException {
        public StorageException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
