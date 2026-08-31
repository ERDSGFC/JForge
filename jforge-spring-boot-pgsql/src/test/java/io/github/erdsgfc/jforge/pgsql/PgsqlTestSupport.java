package io.github.erdsgfc.jforge.pgsql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 真 PostgreSQL 测试基类：连接约定与「无 PG 则跳过」机制。
 *
 * <p>连接参数经系统属性覆盖（默认值针对本地开发实例）：
 * <ul>
 *   <li>{@code jforge.pgsql.url} — 默认 {@code jdbc:postgresql://localhost:5432/jforge}；</li>
 *   <li>{@code jforge.pgsql.user} — 默认 {@code jforge}；</li>
 *   <li>{@code jforge.pgsql.password} — 默认 {@code jforge}。</li>
 * </ul>
 * {@link #requireLocalPostgres()} 在类加载时探测连接（2 秒超时），失败抛
 * JUnit {@code Assumptions} 中止——测试记为 Skipped、构建保持绿色；成功则缓存
 * 探测结果与 {@link HikariDataSource} 单例，供全部测试类复用。</p>
 */
abstract class PgsqlTestSupport {

    /** 连接 URL（可经 {@code -Djforge.pgsql.url=...} 覆盖）。 */
    static final String URL = System.getProperty("jforge.pgsql.url",
            "jdbc:postgresql://localhost:5432/jforge");
    /** 连接用户（可经 {@code -Djforge.pgsql.user=...} 覆盖）。 */
    static final String USER = System.getProperty("jforge.pgsql.user", "jforge");
    /** 连接密码（可经 {@code -Djforge.pgsql.password=...} 覆盖）。 */
    static final String PASS = System.getProperty("jforge.pgsql.password", "jforge");

    private static boolean probed;
    private static boolean reachable;
    private static HikariDataSource dataSource;

    /**
     * 探测本地 PostgreSQL 可达性；不可达时以 {@link Assumptions} 中止测试
     * （Surefire 记为 Skipped）。只在首次调用时真连数据库，结果静态缓存。
     */
    @BeforeAll
    static void requireLocalPostgres() {
        if (!probed) {
            probed = true;
            DriverManager.setLoginTimeout(2);
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASS)) {
                reachable = true;
            } catch (SQLException e) {
                reachable = false;
            }
        }
        Assumptions.assumeTrue(reachable,
                "本地 PostgreSQL 不可用（" + URL + "）——可用 -Djforge.pgsql.url/-Djforge.pgsql.user/"
                        + "-Djforge.pgsql.password 覆盖连接参数，测试将跳过");
    }

    /** 懒建并缓存的数据源（探测通过后才有意义）。 */
    static HikariDataSource dataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(URL);
            config.setUsername(USER);
            config.setPassword(PASS);
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    /** 执行建表 DDL（先 DROP 后 CREATE），测试 {@code @BeforeEach} 用。 */
    static void createTable(String table, String ddl) throws SQLException {
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute("CREATE TABLE " + table + " (" + ddl + ")");
        }
    }

    /** 建 {@code pg_users} 表：覆盖全部受测数据库字段类型（含 PG 原生枚举）。 */
    static void createPgUsersTable() throws SQLException {
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS pg_users");
            // 枚举类型只建一次、不重建:CREATE TYPE 每次产生新 OID,而 pgjdbc 在连接上
            // 缓存类型 OID——重建会让已缓存的连接报 "cache lookup failed for type"。
            try {
                st.execute("CREATE TYPE pg_user_status AS ENUM ('ACTIVE', 'INACTIVE')");
            } catch (SQLException e) {
                if (!"42710".equals(e.getSQLState())) { // 42710 = duplicate_object
                    throw e;
                }
            }
            st.execute("CREATE TABLE pg_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT," +
                    "\"order\" INT," +
                    "city VARCHAR(100)," +
                    "street VARCHAR(100)," +
                    "created_at TIMESTAMP," +
                    "active BOOLEAN," +
                    "balance NUMERIC(12,2)," +
                    "birth_date DATE," +
                    "height DOUBLE PRECISION," +
                    "weight REAL," +
                    "level SMALLINT," +
                    "avatar BYTEA," +
                    "status pg_user_status," +
                    "external_id VARCHAR(36)," +
                    "config_json JSONB)");
        }
    }
}
