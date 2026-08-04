package com.qin.orm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
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
        repo.execute(() -> {
            repo.save(repo.createEntity().name("a").age(1));
            repo.save(repo.createEntity().name("b").age(2));
            return null;
        });

        assertEquals(2, repo.count(), "both rows must be committed");
        assertFalse(repo.isTransactionActive(), "transaction must be cleared after execute");
    }

    @Test
    void executeReturnsCallbackResult() {
        String result = repo.execute(() -> "hello-tx");

        assertEquals("hello-tx", result);
        assertFalse(repo.isTransactionActive());
    }

    @Test
    void executeRollsBackOnException() {
        assertThrows(IllegalStateException.class, () -> repo.execute(() -> {
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
        OrmException ex = assertThrows(OrmException.class, () -> repo.execute(() -> {
            repo.execute(() -> null); // nested begin must fail fast
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
}
