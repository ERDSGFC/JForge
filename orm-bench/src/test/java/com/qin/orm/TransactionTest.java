package com.qin.orm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Integration tests for the programmatic transaction API inherited from
 * {@link BaseRepository} via {@link com.qin.orm.core.TransactionOperations}:
 * the {@code execute} template, manual begin/commit/rollback, thread-bound state,
 * and transaction sharing across repository instances on the same data source.
 */
class TransactionTest {

    private HikariDataSource ds;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
        repo = Repositories.createUserRepository(ds);
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

        // Same thread-local connection: the uncommitted row must be readable.
        assertNotNull(repo.findById(saved.id()));
        repo.rollback();

        assertNull(repo.findById(saved.id()), "row must vanish after rollback");
    }

    @Test
    void differentRepositoryInstancesShareTransaction() {
        // Second repository type on the same DataSource: joins the same thread-local tx.
        com.qin.orm.benchmark.UserRepository benchRepo =
                com.qin.orm.benchmark.Repositories.createUserRepository(ds);

        repo.beginTransaction();
        benchRepo.save(benchRepo.createEntity().name("from-bench").age(6));
        assertEquals(1, repo.count(), "benchmark repo must join the active transaction");
        repo.rollback();

        assertEquals(0, repo.count());
    }

    @Test
    void nestedExecuteRejected() {
        OrmException ex = assertThrows(OrmException.class, () -> repo.execute(conn -> {
            repo.execute(ignored -> null); // nested begin must fail fast
            return null;
        }));

        assertTrue(ex.getMessage().contains("already active"));
        assertFalse(repo.isTransactionActive(), "outer transaction must be cleaned up");
    }

    @Test
    void commitOrRollbackWithoutBeginThrows() {
        assertThrows(OrmException.class, repo::commit);
        assertThrows(OrmException.class, repo::rollback);
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
}
