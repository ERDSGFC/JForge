package io.github.erdsgfc.jforge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import io.github.erdsgfc.jforge.core.JForgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * 编程式事务 API 的集成测试,该 API 经 {@link io.github.erdsgfc.jforge.core.TransactionOperations}
 * 由 {@link BaseRepository} 继承而来:{@code execute} 模板、手动 begin/commit/rollback、
 * 线程绑定的状态,以及同一数据源上多个仓库实例间的事务共享。
 */
class TransactionTest {

    private HikariDataSource ds;
    private JForge jforge;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
            // benchmark 包的 UserRepository（不同仓库实例共享事务测试复用）映射 timed_users。
            st.execute("DROP TABLE IF EXISTS timed_users");
            st.execute("CREATE TABLE timed_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT," +
                    "created_at TIMESTAMP," +
                    "updated_at TIMESTAMP)");
        }
        jforge = new JForge(ds);
        repo = jforge.repository(UserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void executeCommitsOnSuccess() {
        repo.execute(_ -> {
            repo.save(repo.createEntity().name("a").age(1));
            repo.save(repo.createEntity().name("b").age(2));
            return null;
        });

        assertEquals(2, repo.count(), "both rows must be committed");
        assertFalse(repo.isTransactionActive(), "transaction must be cleared after execute");
    }

    @Test
    void executeReturnsCallbackResult() {
        String result = repo.execute(conn -> "hello-tx");

        assertEquals("hello-tx", result);
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
        UserEntity saved = repo.save(repo.createEntity().name("pending").age(5));

        // 同一个线程本地连接:未提交的行必须可读。
        assertNotNull(repo.findById(saved.id()));
        repo.rollback();

        assertNull(repo.findById(saved.id()), "row must vanish after rollback");
    }

    @Test
    void differentRepositoryInstancesShareTransaction() {
        // 同一门面 = 同一 TransactionManager 实例 → 同线程事务共享。
        io.github.erdsgfc.jforge.benchmark.UserRepository benchRepo =
                jforge.repository(io.github.erdsgfc.jforge.benchmark.UserRepository.class);

        repo.beginTransaction();
        benchRepo.save(benchRepo.createEntity().name("from-bench").age(6));
        // benchRepo 映射 timed_users（与根包 repo 的 users 不同表），
        // 但同一门面 = 同一连接：同一事务内的写入对其可见、回滚后消失。
        assertEquals(1, benchRepo.count(), "benchmark repo must join the active transaction");
        repo.rollback();

        assertEquals(0, benchRepo.count());
    }

    @Test
    void differentFacadesDoNotShareTransaction() {
        // 不同门面 = 不同 TransactionManager 实例 → 线程事务互相隔离
        // (新架构与旧全局单例的关键差异:事务共享范围从"全局"收窄到"同门面")。
        io.github.erdsgfc.jforge.benchmark.UserRepository isolatedRepo =
                new JForge(ds).repository(io.github.erdsgfc.jforge.benchmark.UserRepository.class);

        repo.beginTransaction();
        isolatedRepo.save(isolatedRepo.createEntity().name("isolated").age(7));
        repo.rollback();

        // isolatedRepo 映射 timed_users：其写入经独立门面提交，repo 的回滚不影响它。
        assertEquals(1, isolatedRepo.count(),
                "different facade = different manager = no shared transaction");
    }

    @Test
    void nestedExecuteRejected() {
        JForgeException ex = assertThrows(JForgeException.class, () -> repo.execute(conn -> {
            repo.execute(ignored -> null); // nested begin must fail fast
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
            conn.createStatement().executeUpdate("INSERT INTO users (user_name, age) VALUES ('raw', 9)");
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

            conn.rollback(sp); // keep the first insert, discard the second
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

    // ---- 无 Connection 变体(Supplier/Function/Runnable/Consumer) ------------

    @Test
    void executeSupplierRunsInTransaction() {
        String result = repo.execute(() -> {
            repo.save(repo.createEntity().name("sup").age(1));
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, repo.count(), "the supplier body must join and commit the transaction");
        assertFalse(repo.isTransactionActive());
    }

    @Test
    void executeWithFunctionParamPassesExternalValue() {
        String result = repo.execute("fn-param", p -> {
            repo.save(repo.createEntity().name(p).age(2));
            return "done:" + p;
        });

        assertEquals("done:fn-param", result);
        assertEquals(1, repo.count());
        assertEquals("fn-param", repo.findAll().get(0).name());
    }

    @Test
    void executeSupplierRollsBackOnException() {
        assertThrows(IllegalStateException.class, () -> repo.execute(() -> {
            repo.save(repo.createEntity().name("discard").age(3));
            throw new IllegalStateException("boom");
        }));

        assertEquals(0, repo.count(), "the supplier body must roll back like any other transaction body");
        assertFalse(repo.isTransactionActive());
    }

    @Test
    void runRunnableCommits() {
        repo.run(() -> {
            repo.save(repo.createEntity().name("rr").age(4));
        });

        assertEquals(1, repo.count());
        assertFalse(repo.isTransactionActive());
    }

    @Test
    void runWithConsumerParamPassesExternalValue() {
        repo.run("consumer-param", p -> {
            repo.save(repo.createEntity().name(p).age(5));
        });

        assertEquals(1, repo.count());
        assertEquals("consumer-param", repo.findAll().get(0).name());
    }
}
