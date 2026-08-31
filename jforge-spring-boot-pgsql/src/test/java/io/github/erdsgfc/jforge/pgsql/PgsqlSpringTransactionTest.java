package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.core.JForge;
import io.github.erdsgfc.jforge.starter.SpringTransactionManager;
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
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真 PG 上的 Spring 事务集成：{@code @Transactional}（声明式）、
 * {@link TransactionTemplate}（编程式模板）、{@link PlatformTransactionManager}
 * （手动）三种机制经 {@link SpringTransactionManager} join 外层 Spring 事务
 * 控制仓库写入——与 H2 版 {@code SpringTransactionControlTest} 同构，数据源换真 PG。
 */
@SpringJUnitConfig(PgsqlSpringTransactionTest.TxConfiguration.class)
class PgsqlSpringTransactionTest extends PgsqlTestSupport {

    @Configuration
    @EnableTransactionManagement
    static class TxConfiguration {
        @Bean
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(URL);
            config.setUsername(USER);
            config.setPassword(PASS);
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
        PgUserRepository pgUserRepository(DataSource dataSource, SpringTransactionManager ormTxManager) {
            return new JForge(dataSource, ormTxManager).repository(PgUserRepository.class);
        }

        @Bean
        SpringTxService springTxService(PgUserRepository repo) {
            return new SpringTxService(repo);
        }
    }

    @Autowired
    PgUserRepository repo;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    SpringTxService service;

    @BeforeEach
    void setUp() throws SQLException {
        createTable("pg_users", "id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100), age INT, "
                + "\"order\" INT, city VARCHAR(100), street VARCHAR(100), created_at TIMESTAMP");
    }

    /** @Transactional 提交：save 参与外层事务，方法正常返回后数据可见。 */
    @Test
    void transactionalServiceCommits() {
        long count = service.saveTwoAndCount();

        assertEquals(2, count);
        assertEquals(2, repo.count());
    }

    /** @Transactional 回滚：异常抛出让 save 的数据整体回滚。 */
    @Test
    void transactionalServiceRollsBackOnException() {
        assertThrows(IllegalStateException.class, () -> service.saveThenThrow());

        assertEquals(0, repo.count(), "save inside the failed @Transactional must be rolled back");
    }

    /** TransactionTemplate：回滚丢弃写入。 */
    @Test
    void transactionTemplateRollsBack() {
        transactionTemplate.executeWithoutResult(status -> {
            repo.save(repo.createEntity().userName("tmpl").age(1));
            assertEquals(1, repo.count(), "row must be visible inside the transaction");
        });
        assertEquals(1, repo.count(), "committed template must persist");

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            repo.save(repo.createEntity().userName("discard").age(2));
            throw new RuntimeException("abort");
        }));
        assertEquals(1, repo.count(), "rolled-back template must discard the save");
    }

    /** 手动 PlatformTransactionManager：rollback 丢弃 repo.save 数据（join 语义）。 */
    @Test
    void manualTransactionManagerRollback() {
        TransactionStatus status = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            repo.save(repo.createEntity().userName("manual").age(3));
            assertEquals(1, repo.count());
        } finally {
            txManager.rollback(status);
        }

        assertEquals(0, repo.count(), "manual rollback must discard the ORM save");
    }
}
