package io.github.erdsgfc.jforge.pgsql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.SimpleTransactionManager;
import io.github.erdsgfc.jforge.core.TransactionManager;
import io.github.erdsgfc.jforge.pgsql.autoinject.AutoPgUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真 PG 上的 Spring 自动注入：{@code springBeans = true} 使生成的仓库 impl 成为
 * {@code @Repository} bean（构造器 {@code @Autowired} DataSource + TransactionManager），
 * 组件扫描自动注册——save 经 RETURNING 回写生成键。
 */
@SpringJUnitConfig(PgsqlAutoInjectionTest.Config.class)
class PgsqlAutoInjectionTest extends PgsqlTestSupport {

    /** 仅扫描 {@code autoinject} 包（被测的实体/仓库）。 */
    @Configuration
    @ComponentScan("io.github.erdsgfc.jforge.pgsql.autoinject")
    static class Config {
        @Bean
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(URL);
            config.setUsername(USER);
            config.setPassword(PASS);
            return new HikariDataSource(config);
        }

        @Bean
        TransactionManager transactionManager() {
            return new SimpleTransactionManager();
        }
    }

    @Autowired
    AutoPgUserRepository repo;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        createTable("pg_auto_users", "id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100)");
    }

    @Test
    void generatedRepositoryIsAutoInjected() {
        assertNotNull(repo, "generated @Repository impl must be registered as a Spring bean");

        repo.save(repo.createEntity().userName("auto"));
        assertEquals(1, repo.count());
    }
}
