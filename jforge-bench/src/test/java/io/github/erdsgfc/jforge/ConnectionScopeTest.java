package io.github.erdsgfc.jforge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import io.github.erdsgfc.jforge.core.JForgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接作用域 API 的集成测试——{@code executeWithoutTransaction}
 * 及其底层的 beginConnectionScope/endConnectionScope——在同一线程的多次仓库调用间
 * 共享一个连接,且不附带任何事务语义。池借用计数器证明每个作用域恰好使用一个连接,
 * Hikari 池的 MXBean 证明作用域结束时连接被归还。
 */
class ConnectionScopeTest {

    private HikariDataSource pool;
    private CountingDataSource ds;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_scope;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        pool = new HikariDataSource(config);
        try (Connection conn = pool.getConnection(); java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
        ds = new CountingDataSource(pool);
        repo = new JForge(ds).repository(UserRepository.class);
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    /** 当前从池中借出的连接数(全部归还时为 0)。 */
    private int activeConnections() {
        return pool.getHikariPoolMXBean().getActiveConnections();
    }

    @Test
    void multipleCallsShareOneConnection() {
        String result = repo.executeWithoutTransaction(conn -> {
            repo.save(repo.createEntity().name("a").age(1));
            repo.save(repo.createEntity().name("b").age(2));
            repo.save(repo.createEntity().name("c").age(3));
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, ds.borrows(), "all three saves must reuse the single scope connection");
        assertEquals(3, repo.count());
        assertEquals(0, activeConnections(), "scope connection must be returned to the pool");
    }

    @Test
    void autocommitStaysEnabled() throws SQLException {
        repo.executeWithoutTransaction(conn -> {
            assertTrue(conn.getAutoCommit(), "a scope must not disable auto-commit");
            return null;
        });
    }

    @Test
    void noAtomicityStatementsBeforeThrowStayCommitted() {
        assertThrows(IllegalStateException.class, () -> repo.executeWithoutTransaction(conn -> {
            repo.save(repo.createEntity().name("kept").age(1));
            throw new IllegalStateException("boom");
        }));

        assertEquals(1, repo.count(), "no transaction: the insert before the throw must stay committed");
        assertEquals(0, activeConnections(), "scope connection must be returned even on failure");
    }

    @Test
    void connectionReleasedOnException() {
        assertThrows(IllegalStateException.class, () -> repo.executeWithoutTransaction(conn -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(0, activeConnections());
        assertFalse(repo.isTransactionActive(), "a scope must not look like a transaction");
    }

    @Test
    void nestedScopeReusesOuterConnection() {
        repo.executeWithoutTransaction(conn -> {
            assertEquals(1, ds.borrows());
            repo.executeWithoutTransaction(inner -> {
                repo.save(repo.createEntity().name("nested").age(1));
                assertEquals(1, ds.borrows(), "nested scope must reuse the outer connection");
                return null;
            });
            assertEquals(1, ds.borrows(), "connection must survive the inner scope's end");
            return null;
        });

        assertEquals(0, activeConnections(), "connection returned only when the outermost scope ends");
        assertEquals(1, repo.count());
    }

    @Test
    void beginTransactionInsideScopeRejected() {
        JForgeException ex = assertThrows(JForgeException.class, () -> repo.executeWithoutTransaction(conn -> {
            repo.beginTransaction();
            return null;
        }));

        assertTrue(ex.getMessage().contains("connection scope"), ex.getMessage());
        assertFalse(repo.isTransactionActive());
        assertEquals(0, activeConnections(), "the scope must still be cleaned up after the rejected begin");
    }

    @Test
    void scopeInsideTransactionJoinsTransactionConnection() {
        repo.beginTransaction();
        try {
            repo.executeWithoutTransaction(conn -> {
                assertEquals(1, ds.borrows(), "scope must reuse the transaction connection, not borrow");
                repo.save(repo.createEntity().name("in-tx").age(1));
                return null;
            });
        } finally {
            repo.rollback();
        }

        assertEquals(0, repo.count(), "the scope's insert joined the transaction and was rolled back");
        assertEquals(0, activeConnections());
    }

    @Test
    void scopeReturnsCallbackResult() {
        Long id = repo.executeWithoutTransaction(conn ->
                repo.save(repo.createEntity().name("r").age(1)).id());

        assertNotNull(id);
        assertEquals(1, repo.count());
    }

    @Test
    void runWithoutTransactionIsVoidCounterpart() {
        repo.runWithoutTransaction(conn -> {
            repo.save(repo.createEntity().name("v1").age(1));
            repo.save(repo.createEntity().name("v2").age(2));
        });

        assertEquals(1, ds.borrows(), "the void variant must share one connection too");
        assertEquals(2, repo.count());
        assertEquals(0, activeConnections(), "scope connection must be returned to the pool");
    }

    @Test
    void runWithoutTransactionNoParameter() {
        // 无参数版本:lambda 不接收 Connection,只调仓库方法。
        repo.runWithoutTransaction(() -> {
            repo.save(repo.createEntity().name("n1").age(1));
            repo.save(repo.createEntity().name("n2").age(2));
        });

        assertEquals(1, ds.borrows(), "the no-parameter variant must share one connection too");
        assertEquals(2, repo.count());
        assertEquals(0, activeConnections());
    }

    @Test
    void runWithoutTransactionWithParameter() {
        // 有参数版本:外部参数 + 共享 Connection 一起传给 lambda。
        repo.runWithoutTransaction("param-1", (conn, param) -> {
            repo.save(repo.createEntity().name(param).age(3));
            repo.save(repo.createEntity().name(param + "-2").age(4));
        });

        assertEquals(1, ds.borrows(), "the parameterised variant must share one connection too");
        assertEquals(2, repo.count());
        assertEquals(0, activeConnections());
    }

    // ---- 全组合变体:有参数 + 返回 + 带/不带 conn -----------------------------

    @Test
    void executeWithoutTransactionWithParamReturnsValue() {
        // 有参数、带 conn、有返回值(此前缺失的组合)。
        Long id = repo.executeWithoutTransaction("with-conn", (conn, param) -> {
            repo.save(repo.createEntity().name(param).age(5));
            return repo.findAll().get(0).id();
        });

        assertNotNull(id);
        assertEquals(1, ds.borrows());
        assertEquals(0, activeConnections());
    }

    @Test
    void executeWithoutTransactionSupplierSharesConnection() {
        // 无 conn、无参数、有返回值:仓库调用隐式共享作用域连接。
        Long id = repo.executeWithoutTransaction(() -> {
            repo.save(repo.createEntity().name("supplier").age(6));
            return repo.findAll().get(0).id();
        });

        assertNotNull(id);
        assertEquals(1, ds.borrows(), "the no-connection variant must still share one connection");
        assertEquals(0, activeConnections());
    }

    @Test
    void executeWithoutTransactionWithFunctionParam() {
        // 无 conn、有参数、有返回值。
        String result = repo.executeWithoutTransaction("fn-param", p -> {
            repo.save(repo.createEntity().name(p).age(7));
            return "done:" + p;
        });

        assertEquals("done:fn-param", result);
        assertEquals(1, ds.borrows());
        assertEquals(0, activeConnections());
    }

    @Test
    void runWithoutTransactionWithConsumerParam() {
        // 无 conn、有参数、无返回值。
        repo.runWithoutTransaction("consumer-param", p -> {
            repo.save(repo.createEntity().name(p).age(8));
        });

        assertEquals(1, ds.borrows());
        assertEquals(1, repo.count());
        assertEquals(0, activeConnections());
    }

    /**
     * DataSource 装饰器,统计 {@link #getConnection()} 的借用次数,以便测试
     * 证明一个作用域恰好借用了一个池连接。其余所有方法委托给被包装的 Hikari 池。
     */
    private static final class CountingDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger borrows = new AtomicInteger();

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        int borrows() {
            return borrows.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            borrows.incrementAndGet();
            return delegate.getConnection();
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
