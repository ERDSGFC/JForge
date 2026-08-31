package io.github.erdsgfc.jforge.benchmark;
import io.github.erdsgfc.jforge.core.JForge;

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
import java.time.LocalDateTime;
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
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class OrmVsJdbcBenchmark {

    private static final String URL = "jdbc:h2:mem:orm_bench;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private HikariDataSource dataSource;
    private UserRepository repo;
    private TimedUserRepository timedRepo;
    private ManualIdUserRepository manualRepo;
    private long seededId;
    private long manualSeq;

    /** 创建共享的 HikariCP 连接池(带语句缓存)并预置数据。 */
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
            st.execute("DROP TABLE IF EXISTS timed_users");
            st.execute("CREATE TABLE timed_users (" +
                    "id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100), age INT," +
                    "created_at TIMESTAMP, updated_at TIMESTAMP)");
            st.execute("INSERT INTO timed_users (user_name, age, created_at, updated_at) " +
                    "VALUES ('seed', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // 手动主键表:save 无生成键回写路径(与 timed_users 的生成键回写做减法)。
            st.execute("DROP TABLE IF EXISTS manual_id_users");
            st.execute("CREATE TABLE manual_id_users (" +
                    "id BIGINT PRIMARY KEY, user_name VARCHAR(100), age INT)");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM timed_users WHERE user_name = 'seed'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            seededId = rs.getLong(1);
        }
        repo = new JForge(dataSource).repository(UserRepository.class);
        timedRepo = new JForge(dataSource).repository(TimedUserRepository.class);
        manualRepo = new JForge(dataSource).repository(ManualIdUserRepository.class);
    }

    /** 关闭共享连接池。 */
    @TearDown(Level.Trial)
    public void tearDown() {
        dataSource.close();
    }

    // ==================== ORM(生成的仓库) ====================

    /** 生成的插入,带生成主键回写;时间字段由用户手动维护。 */
    @Benchmark
    public UserEntity ormInsert() {
        LocalDateTime now = LocalDateTime.now();
        return repo.save(repo.createEntity().name("heihei").age(25).createdAt(now).updatedAt(now));
    }

    /** ORM update:手动刷新时间字段(与 {@link #jdbcUpdate} 对称)。 */
    @Benchmark
    public boolean ormUpdate() {
        LocalDateTime now = LocalDateTime.now();
        UserEntity user = repo.createEntity().id(seededId).name("heihei").age(25)
                .createdAt(now).updatedAt(now);
        return repo.update(user);
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

    /** 裸 JDBC 插入,读回生成的主键;时间字段手动绑定。 */
    @Benchmark
    public UserEntity jdbcInsert() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO timed_users (user_name, age, created_at, updated_at) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            LocalDateTime now = LocalDateTime.now();
            ps.setString(1, "heihei");
            ps.setInt(2, 25);
            ps.setObject(3, now);
            ps.setObject(4, now);
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

    /** 裸 JDBC update:手动刷新时间字段(与 {@link #ormUpdate} 对称)。 */
    @Benchmark
    public boolean jdbcUpdate() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE timed_users SET user_name=?, age=?, created_at=?, updated_at=? WHERE id=?")) {
            LocalDateTime now = LocalDateTime.now();
            ps.setString(1, "heihei");
            ps.setInt(2, 25);
            ps.setObject(3, now);
            ps.setObject(4, now);
            ps.setLong(5, seededId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 裸 JDBC 按主键查询(按列索引读取)。 */
    @Benchmark
    public UserEntity jdbcFindById() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, user_name, age, created_at, updated_at FROM timed_users WHERE id = ?")) {
            ps.setLong(1, seededId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                // 与 ORM mapRow 精确对称:包装类列(id/age)经 wasNull 回退 null,
                // String 列非空直读、时间列 getObject 天然 null——两边做等价的工作。
                UserEntity user = repo.createEntity();
                long vid = rs.getLong(1);
                user.id(rs.wasNull() ? null : vid);
                user.name(rs.getString(2));
                int vage = rs.getInt(3);
                user.age(rs.wasNull() ? null : vage);
                user.createdAt(rs.getObject(4, LocalDateTime.class));
                user.updatedAt(rs.getObject(5, LocalDateTime.class));
                return user;
            }
        }
    }

    /** 裸 JDBC 查询全部行(按列索引读取)。 */
    @Benchmark
    public List<UserEntity> jdbcFindAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, user_name, age, created_at, updated_at FROM timed_users");
             ResultSet rs = ps.executeQuery()) {
            List<UserEntity> list = new ArrayList<>();
            while (rs.next()) {
                UserEntity user = repo.createEntity();
                long vid = rs.getLong(1);
                user.id(rs.wasNull() ? null : vid);
                user.name(rs.getString(2));
                int vage = rs.getInt(3);
                user.age(rs.wasNull() ? null : vage);
                user.createdAt(rs.getObject(4, LocalDateTime.class));
                user.updatedAt(rs.getObject(5, LocalDateTime.class));
                list.add(user);
            }
            return list;
        }
    }

    // ============ 时间字段：ORM 自动维护 vs 裸 JDBC 手动维护 ============

    /** ORM 插入：created_at/updated_at 由 default 方法自动取值绑定。 */
    @Benchmark
    public TimedUserEntity ormTimedInsert() {
        return timedRepo.save(timedRepo.createEntity().name("heihei").age(25));
    }

    /** ORM update：updated_at 由 default 方法自动刷新（created_at 为 INSERT_ONLY 不触碰）。 */
    @Benchmark
    public boolean ormTimedUpdate() {
        TimedUserEntity user = timedRepo.createEntity().id(seededId).name("heihei").age(25);
        return timedRepo.update(user);
    }

    // ============ 生成键回写路径拆解（手动 id 无回写 vs 生成 id 带回写）============

    /** ORM 插入：手动 id——无 RETURN_GENERATED_KEYS、无生成键回写（与 ormInsert 做减法）。 */
    @Benchmark
    public ManualIdUser ormInsertManualId() {
        return manualRepo.save(manualRepo.createEntity().id(++manualSeq).name("heihei").age(25));
    }

    /** 裸 JDBC 插入：手动 id——无 RETURN_GENERATED_KEYS、无 keys 读取（与 jdbcInsert 做减法）。 */
    @Benchmark
    public ManualIdUser jdbcInsertManualId() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO manual_id_users (id, user_name, age) VALUES (?, ?, ?)")) {
            ManualIdUser user = manualRepo.createEntity();
            user.id(++manualSeq);
            user.name("heihei");
            user.age(25);
            ps.setLong(1, user.id());
            ps.setString(2, user.name());
            ps.setInt(3, user.age());
            ps.executeUpdate();
            return user;
        }
    }
}
