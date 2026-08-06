package com.qin.orm.starter;

import com.qin.orm.OrmException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

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
 * Integration tests for {@link SpringTransactionManager}: the ORM's programmatic
 * transaction API wired through Spring's {@link PlatformTransactionManager} instead
 * of the built-in thread-local implementation. Exercises the {@code execute}
 * template, manual begin/commit/rollback, thread-bound state, and participation in
 * an outer Spring transaction.
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
        // Install the Spring-backed manager as the global ORM manager, mirroring what
        // the auto-configuration does once all singletons are instantiated.
        new SpringTransactionManager(txManager).afterSingletonsInstantiated();
        repo = Repositories.createTestUserRepository(ds);
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

        // Same thread-bound Spring connection: the uncommitted row must be readable.
        assertNotNull(repo.findById(saved.id()));
        repo.rollback();

        assertNull(repo.findById(saved.id()), "row must vanish after rollback");
    }

    @Test
    void joinsOuterSpringTransaction() {
        // Simulates a @Transactional service method: a Spring transaction is already
        // active on the thread; repository work must join it, not start a new one.
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionDefinition());
        repo.save(repo.createEntity().name("outer").age(6));
        assertTrue(repo.isTransactionActive(), "outer Spring tx must be reported active");

        txManager.rollback(outer);

        assertEquals(0, repo.count(), "work joined the outer tx, so the rollback discards it");
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
}