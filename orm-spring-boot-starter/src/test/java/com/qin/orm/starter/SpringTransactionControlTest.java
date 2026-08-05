package com.qin.orm.starter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end proof that repository work can be controlled directly by Spring's
 * three transaction mechanisms — {@code @Transactional} (declarative),
 * {@link TransactionTemplate} (programmatic template) and
 * {@link PlatformTransactionManager} (manual) — once {@link SpringTransactionManager}
 * is installed as the global ORM manager.
 *
 * <p>The context wires a real {@code DataSourceTransactionManager} plus a
 * {@link SpringTransactionManager} bean (which self-installs via
 * {@code SmartInitializingSingleton}), mirroring what the auto-configuration does
 * in a Spring Boot application. All three mechanisms participate in the same
 * transaction because the generated repository code obtains connections through
 * {@code TransactionManager.current().connection(dataSource)} →
 * {@code DataSourceUtils.getConnection}, which returns the Spring-bound
 * transaction connection while one is active.</p>
 */
@SpringJUnitConfig(SpringTransactionControlTest.TxConfiguration.class)
class SpringTransactionControlTest {

    /**
     * Explicit application wiring: data source, Spring transaction manager,
     * {@link TransactionTemplate}, the ORM repository, the {@code @Transactional}
     * service, and the ORM {@link SpringTransactionManager} that installs itself
     * as the global manager once all singletons are instantiated.
     */
    @Configuration
    @EnableTransactionManagement
    static class TxConfiguration {
        @Bean
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:orm_ctl;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            return new HikariDataSource(config);
        }

        @Bean
        DataSourceTransactionManager txManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }

        @Bean
        SpringTransactionManager ormTxManager(PlatformTransactionManager txManager) {
            return new SpringTransactionManager(txManager);
        }

        @Bean
        TestUserRepository testUserRepository(DataSource dataSource) {
            return Repositories.createTestUserRepository(dataSource);
        }

        @Bean
        SpringTxService springTxService(TestUserRepository repo) {
            return new SpringTxService(repo);
        }
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    TestUserRepository repo;

    @Autowired
    SpringTxService txService;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS test_users");
            st.execute("CREATE TABLE test_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
    }

    @Test
    void transactionalAnnotationCommits() {
        txService.commitWork();

        assertEquals(1, repo.count(), "row saved by @Transactional method must commit");
    }

    @Test
    void transactionalAnnotationRollsBack() {
        assertThrows(IllegalStateException.class, txService::rollbackWork);

        assertEquals(0, repo.count(), "row saved before the exception must be rolled back");
    }

    @Test
    void transactionTemplateCommits() {
        transactionTemplate.execute(status -> {
            repo.save(repo.createEntity().name("tmpl").age(1));
            return null;
        });

        assertEquals(1, repo.count());
    }

    @Test
    void transactionTemplateRollsBack() {
        assertThrows(RuntimeException.class, () -> transactionTemplate.execute(status -> {
            repo.save(repo.createEntity().name("tmpl").age(2));
            throw new RuntimeException("boom");
        }));

        assertEquals(0, repo.count(), "row saved before the exception must be rolled back");
    }

    @Test
    void manualPlatformTransactionManagerRollsBack() {
        TransactionStatus status = txManager.getTransaction(new DefaultTransactionDefinition());
        repo.save(repo.createEntity().name("manual").age(3));
        txManager.rollback(status);

        assertEquals(0, repo.count(), "manual rollback must discard the row");
    }
}
