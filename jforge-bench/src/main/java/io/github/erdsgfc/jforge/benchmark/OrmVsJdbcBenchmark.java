package io.github.erdsgfc.jforge.benchmark;
import io.github.erdsgfc.jforge.JForge;

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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ORM(编译期生成的仓库)与裸 JDBC 对比:同一张表、相同的操作、
 * 同一个连接池(两者均启用语句缓存)。生成的仓库输出直接 JDBC 代码,
 * 因此框架开销应可忽略不计。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class OrmVsJdbcBenchmark {

    private static final String URL = "jdbc:h2:mem:orm_bench;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private HikariDataSource dataSource;
    private UserRepository repo;
    private long seededId;

    /** 创建共享的 HikariCP 连接池(带语句缓存)并预置一行数据。 */
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
        repo = new JForge(dataSource).repository(UserRepository.class);
    }

    /** 关闭共享连接池。 */
    @TearDown(Level.Trial)
    public void tearDown() {
        dataSource.close();
    }

    // ==================== ORM(生成的仓库) ====================

    /** 生成的插入,带生成主键回写。 */
    @Benchmark
    public UserEntity ormInsert() {
        return repo.save(repo.createEntity().name("heihei").age(25));
    }

    /** 按主键查询的生成代码。 */
    @Benchmark
    public UserEntity ormFindById() {
        return repo.findById(seededId);
    }

    /** 查询全部行的生成代码。 */
    @Benchmark
    public List<UserEntity> ormFindAll() {
        return repo.findAll();
    }

    // ==================== 裸 JDBC ====================

    /** 裸 JDBC 插入,读回生成的主键。 */
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
                UserEntity user = repo.createEntity();
                user.id(keys.getLong(1));
                user.name("heihei");
                user.age(25);
                return user;
            }
        }
    }

    /** 裸 JDBC 按主键查询(按列索引读取)。 */
    @Benchmark
    public UserEntity jdbcFindById() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, user_name, age FROM users WHERE id = ?")) {
            ps.setLong(1, seededId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UserEntity user = repo.createEntity();
                user.id(rs.getLong(1));
                user.name(rs.getString(2));
                user.age(rs.getInt(3));
                return user;
            }
        }
    }

    /** 裸 JDBC 查询全部行(按列索引读取)。 */
    @Benchmark
    public List<UserEntity> jdbcFindAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, user_name, age FROM users");
             ResultSet rs = ps.executeQuery()) {
            List<UserEntity> list = new ArrayList<>();
            while (rs.next()) {
                UserEntity user = repo.createEntity();
                user.id(rs.getLong(1));
                user.name(rs.getString(2));
                user.age(rs.getInt(3));
                list.add(user);
            }
            return list;
        }
    }
}
