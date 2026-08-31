package io.github.erdsgfc.jforge.logsql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证以 {@code @JForgeConfig(logSql=true)} 生成的仓库仍能编译并正常运行
 * (其实现类携带 SLF4J Logger,并输出 DEBUG/WARN 级别的 SQL 日志)。
 */
class LogSqlTest {

    private HikariDataSource ds;
    private LogUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_logsql;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS log_users");
            st.execute("CREATE TABLE log_users (id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100))");
        }
        repo = new JForge(ds).repository(LogUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void logSqlEnabledRepositoryWorks() {
        repo.save(repo.createEntity().name("logged"));
        assertEquals(1, repo.count());
    }
}
