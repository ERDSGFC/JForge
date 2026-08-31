package io.github.erdsgfc.jforge.starter;
import io.github.erdsgfc.jforge.core.JForge;

import io.github.erdsgfc.jforge.core.JForgeException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpringTransactionManager} 的集成测试：ORM 的编程式事务 API 通过 Spring 的
 * {@link PlatformTransactionManager} 而非内置的 thread-local 实现驱动。覆盖
 * {@code execute} 模板、手动 begin/commit/rollback、线程绑定状态以及外层 Spring
 * 事务的参与。
 */
class SpringTransactionManagerTest {

    private HikariDataSource ds;
    private PlatformTransactionManager txManager;
    private TestUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_tx_spring;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS test_users");
            st.execute("CREATE TABLE test_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
        txManager = new DataSourceTransactionManager(ds);
        // 新架构:管理器经 JForge 实例传入生成实现(构造器注入),无全局状态。
        repo = new JForge(ds, new SpringTransactionManager(txManager)).repository(TestUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void executeCommitsOnSuccess() {
        repo.execute(conn -> {
            repo.save(repo.createEntity().name("a").age(1));
            repo.save(repo.createEntity().name("b").age(2));
            return null;
        });

        assertEquals(2, repo.count(), "both rows must be committed");
        assertFalse(repo.isTransactionActive(), "transaction must be cleared after execute");
    }

    @Test
    void executeReturnsCallbackResult() {
        String result = repo.execute(conn -> "hello-spring-tx");

        assertEquals("hello-spring-tx", result);
        assertFalse(repo.isTransactionActive());
    }

    @Test
    void executeRollsBackOnException() {
        assertThrows(IllegalStateException.class, () -> repo.execute(conn -> {
            repo.save(repo.createEntity().name("a").age(1));
            throw new IllegalStateException("boom");
        }));

        assertEquals(0, repo.count(), "the partially-executed body must be rolled back");
        assertFalse(repo.isTransactionActive(), "rollback must clear the thread state");
    }

    @Test
    void manualBeginCommitPersists() {
        repo.beginTransaction();
        assertTrue(repo.isTransactionActive());
        repo.save(repo.createEntity().name("manual").age(3));
        repo.commit();

        assertFalse(repo.isTransactionActive());
        assertEquals(1, repo.count());
    }

    @Test
    void manualBeginRollbackDiscards() {
        repo.beginTransaction();
        repo.save(repo.createEntity().name("discarded").age(4));
        repo.rollback();

        assertFalse(repo.isTransactionActive());
        assertEquals(0, repo.count());
    }

    @Test
    void uncommittedDataVisibleWithinTransaction() {
        repo.beginTransaction();
        TestUser saved = repo.save(repo.createEntity().name("pending").age(5));

        // 同一个线程绑定的 Spring 连接：未提交的行必须可读。
        assertNotNull(repo.findById(saved.id()));
        repo.rollback();

        assertNull(repo.findById(saved.id()), "row must vanish after rollback");
    }

    @Test
    void joinsOuterSpringTransaction() {
        // 模拟 @Transactional 服务方法：线程上已有活动 Spring 事务；
        // 仓库工作必须加入它，而不是新开一个。
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionDefinition());
        repo.save(repo.createEntity().name("outer").age(6));
        assertTrue(repo.isTransactionActive(), "outer Spring tx must be reported active");

        txManager.rollback(outer);

        assertEquals(0, repo.count(), "work joined the outer tx, so the rollback discards it");
    }

    @Test
    void scopeSharesOneConnectionWithoutTransaction() {
        int activeBefore = ds.getHikariPoolMXBean().getActiveConnections();
        repo.executeWithoutTransaction(conn -> {
            assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections(),
                    "the scope must borrow exactly one connection");
            repo.save(repo.createEntity().name("s1").age(1));
            repo.save(repo.createEntity().name("s2").age(2));
            assertFalse(repo.isTransactionActive(), "a scope is not a transaction");
            return null;
        });

        assertEquals(2, repo.count(), "no transaction: both inserts are committed independently");
        assertEquals(activeBefore, ds.getHikariPoolMXBean().getActiveConnections(),
                "scope connection must be returned to the pool");
        assertFalse(TransactionSynchronizationManager.hasResource(ds),
                "the scope holder must be unbound");
    }

    @Test
    void scopeInsideOuterSpringTransactionJoinsIt() {
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            repo.executeWithoutTransaction(conn -> {
                assertTrue(repo.isTransactionActive(), "the outer Spring tx must stay active");
                assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections(),
                        "the scope must reuse the transaction connection, not borrow another");
                repo.save(repo.createEntity().name("joined").age(3));
                return null;
            });
        } finally {
            txManager.rollback(outer);
        }

        assertEquals(0, repo.count(), "the scope's work joined the outer tx and was rolled back");
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections());
        assertFalse(TransactionSynchronizationManager.hasResource(ds),
                "the scope must not have unbound the transaction's connection");
    }

    @Test
    void executeSupplierJoinsOuterSpringTransaction() {
        // 无 Connection 变体在 Spring 路径下必须同样 join 外层事务。
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            String result = repo.execute(() -> {
                repo.save(repo.createEntity().name("sup-spring").age(11));
                return "done";
            });
            assertEquals("done", result);
            assertTrue(repo.isTransactionActive(), "the supplier body must join the outer tx");
        } finally {
            txManager.rollback(outer);
        }

        assertEquals(0, repo.count(), "the supplier body's work joined the outer tx and was rolled back");
    }

    @Test
    void scopeSupplierSharesSpringConnection() {
        // 无 Connection 的作用域变体在 Spring 路径下仍借用单个连接。
        repo.runWithoutTransaction(() -> {
            assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections(),
                    "the no-connection scope variant must still borrow exactly one connection");
            repo.save(repo.createEntity().name("scope-sup").age(12));
        });

        assertEquals(1, repo.count());
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections());
        assertFalse(TransactionSynchronizationManager.hasResource(ds),
                "the scope holder must be unbound");
    }

    @Test
    void nestedExecuteRejected() {
        JForgeException ex = assertThrows(JForgeException.class, () -> repo.execute(conn -> {
            repo.execute(ignored -> null); // 嵌套 begin 必须快速失败
            return null;
        }));

        assertTrue(ex.getMessage().contains("already active"));
        assertFalse(repo.isTransactionActive(), "outer transaction must be cleaned up");
    }

    @Test
    void commitOrRollbackWithoutBeginThrows() {
        assertThrows(JForgeException.class, repo::commit);
        assertThrows(JForgeException.class, repo::rollback);
        assertFalse(repo.isTransactionActive());
    }

    @Test
    void executeCallbackRawSqlJoinsAndRollsBack() {
        assertThrows(IllegalStateException.class, () -> repo.execute(conn -> {
            conn.createStatement().executeUpdate("INSERT INTO test_users (user_name, age) VALUES ('raw', 9)");
            throw new IllegalStateException("boom");
        }));

        assertEquals(0, repo.count(), "raw SQL through the execute connection must join and roll back");
    }

    @Test
    void executeCallbackAllowsSavepointRollback() {
        repo.execute(conn -> {
            repo.save(repo.createEntity().name("keep").age(1));

            Savepoint sp = conn.setSavepoint("after-keep");
            repo.save(repo.createEntity().name("discard").age(2));

            conn.rollback(sp); // 保留第一次插入，丢弃第二次
            return null;
        });

        assertEquals(1, repo.count());
        assertEquals("keep", repo.findAll().get(0).name());
    }

    @Test
    void executeWithExternalParameter() {
        String result = repo.execute("external", (conn, param) -> {
            repo.save(repo.createEntity().name(param).age(7));
            return "done:" + param;
        });

        assertEquals("done:external", result);
        assertEquals(1, repo.count());
        assertEquals("external", repo.findAll().get(0).name());
    }

    @Test
    void manualBeginLeftToOuterTransactionIsClearedOnCompletion() {
        // 模拟一个外层 @Transactional 边界在 ORM 未提交/回滚其加入的事务的情况下完成：
        // 完成钩子必须清除过期的 ORM 状态，使同一线程在下一次事务中不会误报
        // "already active"。
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionDefinition());
        repo.beginTransaction();               // 加入外层事务，存储状态
        assertTrue(repo.isTransactionActive());
        txManager.commit(outer);               // 外层完成；ORM 状态必须被清除

        assertFalse(repo.isTransactionActive(), "stale status must be cleared by the completion hook");
        repo.execute(conn -> {                 // 不得误报 "already active"
            repo.save(repo.createEntity().name("after").age(10));
            return null;
        });
        assertEquals(1, repo.count());
    }

    @Test
    void runWithoutReturnValue() {
        repo.run(conn -> {
            repo.save(repo.createEntity().name("no-return").age(8));
        });

        assertEquals(1, repo.count());
        assertEquals("no-return", repo.findAll().get(0).name());
    }

    @Test
    void runWithExternalParameter() {
        repo.run("param-name", (conn, param) -> {
            repo.save(repo.createEntity().name(param).age(9));
        });

        assertEquals(1, repo.count());
        assertEquals("param-name", repo.findAll().get(0).name());
    }

    @Test
    void markRollbackOnlyRollsBackButReturnsResult() {
        String result = repo.execute(conn -> {
            repo.save(repo.createEntity().name("discard").age(1));
            repo.markRollbackOnly();
            return "aborted";
        });

        assertEquals("aborted", result, "execute must return normally despite the rollback");
        assertEquals(0, repo.count(), "the marked transaction must be rolled back");
        assertFalse(repo.isTransactionActive());
    }

    @Test
    void isRollbackOnlyReflectsMark() {
        repo.beginTransaction();
        assertFalse(repo.isRollbackOnly());
        repo.markRollbackOnly();
        assertTrue(repo.isRollbackOnly());
        repo.rollback();
        assertFalse(repo.isRollbackOnly());
    }
}