package com.qin.orm.benchmark;

import com.qin.orm.Session;
import com.qin.orm.SessionFactory;
import com.qin.orm.UserEntity;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ORM vs raw JDBC: same table, same operations, same connection pool (both with
 * statement caching). Measures whether the ORM framework overhead stays within the
 * acceptance budget (<=5%) using the standard single-call style.
 *
 * NOTE: the insert benchmarks grow the table, so findAll scans more rows in later
 * benchmarks; both sides are equally affected (alphabetical order runs JDBC first).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class OrmVsJdbcBenchmark {

    private static final String URL = "jdbc:h2:mem:orm_bench;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Session session;
    private HikariDataSource dataSource;
    private long seededId;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setMaximumPoolSize(4);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100), age INT)");
            st.execute("INSERT INTO users (user_name, age) VALUES ('seed', 1)");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE user_name = 'seed'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            seededId = rs.getLong(1);
        }
        session = SessionFactory.open(dataSource);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
        dataSource.close();
    }

    // ==================== ORM ====================

    @Benchmark
    public UserEntity ormInsert() {
        UserEntity user = new UserEntity();
        user.setName("heihei");
        user.setAge(25);
        session.insert(user);
        return user;
    }

    @Benchmark
    public UserEntity ormFindById() {
        return session.findById(UserEntity.class, seededId);
    }

    @Benchmark
    public List<UserEntity> ormFindAll() {
        return session.findAll(UserEntity.class);
    }

    // ==================== Raw JDBC ====================

    @Benchmark
    public UserEntity jdbcInsert() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (user_name, age) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "heihei");
            ps.setInt(2, 25);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                UserEntity user = new UserEntity();
                user.setId(keys.getLong(1));
                user.setName("heihei");
                user.setAge(25);
                return user;
            }
        }
    }

    @Benchmark
    public UserEntity jdbcFindById() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, user_name, age FROM users WHERE id = ?")) {
            ps.setLong(1, seededId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UserEntity user = new UserEntity();
                user.setId(rs.getLong(1));
                user.setName(rs.getString(2));
                user.setAge(rs.getInt(3));
                return user;
            }
        }
    }

    @Benchmark
    public List<UserEntity> jdbcFindAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, user_name, age FROM users");
             ResultSet rs = ps.executeQuery()) {
            List<UserEntity> list = new ArrayList<>();
            while (rs.next()) {
                UserEntity user = new UserEntity();
                user.setId(rs.getLong(1));
                user.setName(rs.getString(2));
                user.setAge(rs.getInt(3));
                list.add(user);
            }
            return list;
        }
    }
}
