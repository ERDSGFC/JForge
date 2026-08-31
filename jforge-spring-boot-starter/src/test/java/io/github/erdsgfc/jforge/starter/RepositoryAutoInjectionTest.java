package io.github.erdsgfc.jforge.starter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.SimpleTransactionManager;
import io.github.erdsgfc.jforge.core.TransactionManager;
import io.github.erdsgfc.jforge.starter.autoinject.AutoUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证当包上标有 {@code @JForgeConfig(springBeans = true)} 时，生成的仓库实现
 * （标注 {@code @Repository}、构造器带 {@code @Autowired} 并接收 {@code DataSource} +
 * {@code TransactionManager}）会被 Spring 组件扫描自动注册且可注入——无需手动
 * {@code JForge} / 仓库创建。
 */
@SpringJUnitConfig(RepositoryAutoInjectionTest.Config.class)
class RepositoryAutoInjectionTest {

    /** 仅扫描 {@code autoinject} 包（被测的实体/仓库）。 */
    @Configuration
    @ComponentScan("io.github.erdsgfc.jforge.starter.autoinject")
    static class Config {
        @Bean
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:orm_autoinject;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
            return new HikariDataSource(config);
        }

        @Bean
        TransactionManager transactionManager() {
            return new SimpleTransactionManager();
        }
    }

    @Autowired
    AutoUserRepository repo;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS auto_users");
            st.execute("CREATE TABLE auto_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
    }

    @Test
    void generatedRepositoryIsAutoInjected() {
        assertNotNull(repo, "generated @Repository impl must be registered as a Spring bean");

        repo.save(repo.createEntity().name("auto").age(1));
        assertEquals(1, repo.count());
    }
}
