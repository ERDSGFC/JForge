package io.github.erdsgfc.jforge.clsconfig;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.JForge;
import io.github.erdsgfc.jforge.clsconfig.sub.ClsEntity;
import io.github.erdsgfc.jforge.clsconfig.sub.ClsPlainRepository;
import io.github.erdsgfc.jforge.clsconfig.sub.ClsRepository;
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

/**
 * 方案 B 语义:标注在普通类上的 {@code @JForgeConfig} 映射到其所在包——
 * 子包仓库(无自身配置)经包链继承父包中 {@link ClsConfig} 类的 batchSize=1。
 */
class ClsConfigTest {

    private HikariDataSource pool;
    private CountingDataSource ds;
    private ClsRepository repo;          // 接口自身 @JForgeConfig(batchSize=1)
    private ClsPlainRepository plainRepo; // 无标注,继承父包 package-info(batchSize=3)

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_cls_config;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        pool = new HikariDataSource(config);
        try (Connection conn = pool.getConnection(); java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS cls_entity");
            st.execute("CREATE TABLE cls_entity (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "name VARCHAR(100))");
        }
        ds = new CountingDataSource(pool);
        JForge jforge = new JForge(ds);
        repo = jforge.repository(ClsRepository.class);
        plainRepo = jforge.repository(ClsPlainRepository.class);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    @Test
    void interfaceAnnotationOverridesPackageChain() {
        // 接口自身 @JForgeConfig(batchSize=1) 优先于父包 package-info 的 batchSize=3。
        List<ClsEntity> entities = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            entities.add(repo.createEntity().name("c" + i));
        }
        repo.save(entities);

        assertEquals(2, ds.batches(), "接口自身配置必须优先(2 行 → 2 次 flush)");
        assertEquals(2, repo.count());
    }

    @Test
    void plainRepositoryInheritsPackageChain() {
        // 无自身标注的仓库:经包链继承父包 package-info 的 batchSize=3。
        List<ClsEntity> entities = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            entities.add(plainRepo.createEntity().name("p" + i));
        }
        plainRepo.save(entities);

        assertEquals(1, ds.batches(), "包链继承 batchSize=3(2 行 → 1 次 flush)");
        assertEquals(2, plainRepo.count());
    }

    /** DataSource 装饰器:计数 executeBatch 调用。 */
    private static final class CountingDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger batches = new AtomicInteger();

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        int batches() {
            return batches.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(real, args);
                            if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement ps) {
                                return countingStatement(ps);
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private PreparedStatement countingStatement(PreparedStatement real) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("executeBatch")) {
                            batches.incrementAndGet();
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
