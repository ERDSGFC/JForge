package io.github.erdsgfc.jforge.batch;
import io.github.erdsgfc.jforge.JForge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.UserEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for the JDBC batch insert of {@code save(List<T>)}: the
 * batch size resolution (method {@code @BatchSize} &gt; type {@code @BatchSize}
 * &gt; {@code @JForgeConfig.batchSize} &gt; 0), chunked {@code addBatch()/
 * executeBatch()} flushing, generated-id write-back, and the single-connection
 * guarantee in both the batched and the non-batched paths. A {@code DataSource}
 * decorator counts pool borrows; a {@code Connection} proxy counts
 * {@code executeBatch()} and {@code executeUpdate()} calls.
 */
class ConnectionBatchTest {

    private HikariDataSource pool;
    private CountingDataSource ds;
    private PackageBatchRepository packageRepo;   // 全局 @JForgeConfig(batchSize = 2)(BatchConfig 类)
    private TypeBatchRepository typeRepo;         // @BatchSize(3) 类型级
    private MethodBatchRepository methodRepo;     // @BatchSize(5) 方法级
    private NoBatchRepository noBatchRepo;        // @BatchSize(0) 显式关闭批处理

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_batch;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        pool = new HikariDataSource(config);
        try (Connection conn = pool.getConnection(); java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS batch_users");
            st.execute("CREATE TABLE batch_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "name VARCHAR(100))");
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
        ds = new CountingDataSource(pool);
        packageRepo = new JForge(ds).repository(PackageBatchRepository.class);
        typeRepo = new JForge(ds).repository(TypeBatchRepository.class);
        methodRepo = new JForge(ds).repository(MethodBatchRepository.class);
        noBatchRepo = new JForge(ds).repository(NoBatchRepository.class);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private List<BatchUser> batchUsers(int count) {
        List<BatchUser> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entities.add(packageRepo.createEntity().name("u" + i));
        }
        return entities;
    }

    // ---- batch size resolution ----------------------------------------------

    @Test
    void packageConfigFlushesInChunks() {
        List<BatchUser> saved = packageRepo.save(batchUsers(5));

        // 先断言池计数:count() 会再借一个连接,必须放在最后。
        assertEquals(1, ds.borrows(), "one connection for the whole batch insert");
        assertEquals(3, ds.batches(), "batchSize=2 with 5 rows: 2+2+1 flushes");
        assertEquals(0, ds.updates(), "no per-row executeUpdate in batch mode");
        assertEquals(5, packageRepo.count());
        for (BatchUser entity : saved) {
            assertNotNull(entity.id(), "generated ids must be written back to every entity");
        }
    }

    @Test
    void typeLevelBatchSizeOverridesPackage() {
        List<BatchUser> saved = typeRepo.save(batchUsers(5));

        assertEquals(1, ds.borrows());
        assertEquals(2, ds.batches(), "@BatchSize(3) with 5 rows: 3+2 flushes");
        assertEquals(5, typeRepo.count());
        for (BatchUser entity : saved) {
            assertNotNull(entity.id());
        }
    }

    @Test
    void methodLevelBatchSizeOverridesAll() {
        List<BatchUser> saved = methodRepo.save(batchUsers(5));

        assertEquals(1, ds.borrows());
        assertEquals(1, ds.batches(), "@BatchSize(5) with 5 rows: a single flush");
        assertEquals(5, methodRepo.count());
        for (BatchUser entity : saved) {
            assertNotNull(entity.id());
        }
    }

    // ---- no batching (default 0) --------------------------------------------

    @Test
    void fallsBackToDefaultsWithoutPackageChain() {
        // 主树 UserRepository 的包链(io.github.erdsgfc.jforge)无 package-info 配置,
        // 且整个编译存在多个 @JForgeConfig(单标注全局兜底不生效)→ 默认 batchSize=50。
        io.github.erdsgfc.jforge.UserRepository repo =
                new JForge(ds).repository(io.github.erdsgfc.jforge.UserRepository.class);
        List<UserEntity> entities = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            entities.add(repo.createEntity().name("n" + i).age(i));
        }

        List<UserEntity> saved = repo.save(entities);

        // count() 会再借一个连接,放在池计数断言之后。
        assertEquals(1, ds.borrows(), "one connection for the whole batch insert");
        assertEquals(1, ds.batches(), "3 rows fit in one default-size batch");
        assertEquals(0, ds.updates(), "no per-row executeUpdate in batch mode");
        assertEquals(3, repo.count());
        for (UserEntity entity : saved) {
            assertNotNull(entity.id(), "generated ids must be written back per row");
        }
    }

    @Test
    void explicitZeroDisablesBatching() {
        List<BatchUser> saved = noBatchRepo.save(batchUsers(3));

        assertEquals(1, ds.borrows(), "@BatchSize(0) must still share one connection");
        assertEquals(0, ds.batches(), "no executeBatch when batching is disabled");
        assertEquals(3, ds.updates(), "one executeUpdate per row");
        assertEquals(3, noBatchRepo.count());
        for (BatchUser entity : saved) {
            assertNotNull(entity.id(), "generated ids must be written back per row");
        }
    }

    @Test
    void emptyListTouchesNoConnection() {
        packageRepo.save(List.of());

        assertEquals(0, ds.borrows(), "an empty batch must not acquire a connection");
        assertEquals(0, packageRepo.count());
    }

    /**
     * DataSource decorator counting pool borrows plus, via a {@code Connection}
     * proxy, {@code executeBatch()} and {@code executeUpdate()} invocations. The
     * proxy unwraps {@link InvocationTargetException} so callers see the original
     * {@link SQLException}.
     */
    private static final class CountingDataSource implements DataSource {
        private final HikariDataSource delegate;
        private final AtomicInteger borrows = new AtomicInteger();
        private final AtomicInteger batches = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();

        CountingDataSource(HikariDataSource delegate) {
            this.delegate = delegate;
        }

        int borrows() {
            return borrows.get();
        }

        int batches() {
            return batches.get();
        }

        int updates() {
            return updates.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            borrows.incrementAndGet();
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(real, args);
                            // 生成的代码在 PreparedStatement 上调用 executeBatch/executeUpdate:
                            // 把 prepareStatement 返回的语句也包上计数代理,否则计数永远为 0。
                            if (method.getName().equals("prepareStatement")
                                    && result instanceof PreparedStatement statement) {
                                return countingStatement(statement);
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        /** Wraps a statement in a proxy counting {@code executeBatch}/{@code executeUpdate}. */
        private PreparedStatement countingStatement(PreparedStatement real) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("executeBatch")) {
                            batches.incrementAndGet();
                        } else if (method.getName().equals("executeUpdate")) {
                            updates.incrementAndGet();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            borrows.incrementAndGet();
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}