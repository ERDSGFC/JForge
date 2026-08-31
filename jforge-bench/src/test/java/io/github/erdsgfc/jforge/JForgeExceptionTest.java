package io.github.erdsgfc.jforge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import io.github.erdsgfc.jforge.core.JForgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 {@link JForgeException}:错误 {@link JForgeException.Code} 分类、SQL 上下文与
 * 根因传播——既作为独立的值类,也作为生成仓库代码抛出的异常
 * (其消息中嵌入了操作名、表名和 SQL)。
 */
class JForgeExceptionTest {

    // ==================== 单元测试：构造器 + 访问器 ====================

    @Test
    void codeSqlAndCauseArePreserved() {
        SQLException cause = new SQLException("boom");
        JForgeException ex = new JForgeException(JForgeException.Code.SQL, "save failed", "INSERT INTO users", cause);

        assertEquals(JForgeException.Code.SQL, ex.code());
        assertEquals("INSERT INTO users", ex.sql());
        assertEquals("save failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void defaultCodeIsSqlAndSqlContextIsNull() {
        JForgeException ex = new JForgeException("msg");

        assertEquals(JForgeException.Code.SQL, ex.code());
        assertNull(ex.sql());

        assertEquals(JForgeException.Code.SQL, new JForgeException("msg", new SQLException("x")).code());
    }

    @Test
    void categoryConstructors() {
        assertEquals(JForgeException.Code.TRANSACTION,
                new JForgeException(JForgeException.Code.TRANSACTION, "m").code());
        assertEquals(JForgeException.Code.CONNECTION,
                new JForgeException(JForgeException.Code.CONNECTION, "m", new SQLException("x")).code());
    }

    // ==================== 集成测试：生成代码消息带上下文 ====================

    private HikariDataSource ds;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_exception;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            // NOT NULL 约束：save(name=null) 触发真实 SQLException，验证生成的异常消息。
            st.execute("CREATE TABLE users (id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100) NOT NULL, age INT)");
        }
        repo = new JForge(ds).repository(UserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void generatedSaveErrorCarriesTableAndSqlContext() {
        JForgeException ex = assertThrows(JForgeException.class,
                () -> repo.save(repo.createEntity().name(null).age(1)));

        assertEquals(JForgeException.Code.SQL, ex.code());
        assertTrue(ex.getMessage().contains("save on table 'users'"), ex.getMessage());
        // 生成 SQL 的标识符按方言引用符包裹（POSTGRESQL 方言双引号）。
        assertTrue(ex.getMessage().contains("INSERT INTO \"users\""), ex.getMessage());
        assertTrue(ex.sql().contains("INSERT INTO \"users\""), ex.sql());
        assertNotNull(ex.getCause(), "underlying SQLException must be preserved as the cause");
    }
}
