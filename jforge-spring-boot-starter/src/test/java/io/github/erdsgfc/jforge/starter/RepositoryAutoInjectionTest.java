package io.github.erdsgfc.jforge.starter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Verifies that with {@code @JForgeConfig(springBeans = true)} on a package, the
 * generated repository impl (annotated {@code @Repository}, {@code @Autowired}
 * constructor taking the {@code DataSource}) is auto-registered by Spring component
 * scanning and injectable — no manual {@code Repositories.createXxxRepository} needed.
 */
@SpringJUnitConfig(RepositoryAutoInjectionTest.Config.class)
class RepositoryAutoInjectionTest {

    /** Scans only the {@code autoinject} package (the entities/repository under test). */
    @Configuration
    @ComponentScan("io.github.erdsgfc.jforge.starter.autoinject")
    static class Config {
        @Bean
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:orm_autoinject;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            return new HikariDataSource(config);
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
