package io.github.erdsgfc.jforge.starter;
import io.github.erdsgfc.jforge.core.JForge;

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
 * 端到端验证：一旦 {@link SpringTransactionManager} 被安装为全局 ORM 管理器，
 * 仓库工作就可由 Spring 的三种事务机制直接控制——{@code @Transactional}
 * （声明式）、{@link TransactionTemplate}（编程式模板）和
 * {@link PlatformTransactionManager}（手动）。
 *
 * <p>测试上下文装配了真实的 {@code DataSourceTransactionManager} 与一个
 * {@link SpringTransactionManager} Bean，模拟 Spring Boot 应用中自动配置的行为。
 * 三种机制参与同一个事务，因为生成的仓库代码通过注入的 {@link TransactionManager} →
 * {@code DataSourceUtils.getConnection} 获取连接，在存在活动事务时它返回
 * Spring 绑定的事务连接。</p>
 */
@SpringJUnitConfig(SpringTransactionControlTest.TxConfiguration.class)
class SpringTransactionControlTest {

    /**
     * 显式的应用装配：数据源、Spring 事务管理器、{@link TransactionTemplate}、
     * ORM 仓库、{@code @Transactional} 服务，以及注入仓库门面的 ORM
     * {@link SpringTransactionManager}。
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
        TestUserRepository testUserRepository(DataSource dataSource, SpringTransactionManager ormTxManager) {
            return new JForge(dataSource, ormTxManager).repository(TestUserRepository.class);
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
