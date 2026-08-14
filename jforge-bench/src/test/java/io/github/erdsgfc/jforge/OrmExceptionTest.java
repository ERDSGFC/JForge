package io.github.erdsgfc.jforge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Tests {@link OrmException}: its error {@link OrmException.Code} category, SQL context
 * and cause propagation — both as a standalone value class and as thrown by generated
 * repository code (whose message embeds operation, table name and SQL).
 */
class OrmExceptionTest {

    // ==================== 单元测试：构造器 + 访问器 ====================

    @Test
    void codeSqlAndCauseArePreserved() {
        SQLException cause = new SQLException("boom");
        OrmException ex = new OrmException(OrmException.Code.SQL, "save failed", "INSERT INTO users", cause);

        assertEquals(OrmException.Code.SQL, ex.code());
        assertEquals("INSERT INTO users", ex.sql());
        assertEquals("save failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void defaultCodeIsSqlAndSqlContextIsNull() {
        OrmException ex = new OrmException("msg");

        assertEquals(OrmException.Code.SQL, ex.code());
        assertNull(ex.sql());

        assertEquals(OrmException.Code.SQL, new OrmException("msg", new SQLException("x")).code());
    }

    @Test
    void categoryConstructors() {
        assertEquals(OrmException.Code.TRANSACTION,
                new OrmException(OrmException.Code.TRANSACTION, "m").code());
        assertEquals(OrmException.Code.CONNECTION,
                new OrmException(OrmException.Code.CONNECTION, "m", new SQLException("x")).code());
    }

    // ==================== 集成测试：生成代码消息带上下文 ====================

    private HikariDataSource ds;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_exception;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
        OrmException ex = assertThrows(OrmException.class,
                () -> repo.save(repo.createEntity().name(null).age(1)));

        assertEquals(OrmException.Code.SQL, ex.code());
        assertTrue(ex.getMessage().contains("save on table 'users'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("INSERT INTO users"), ex.getMessage());
        assertTrue(ex.sql().contains("INSERT INTO users"), ex.sql());
        assertNotNull(ex.getCause(), "underlying SQLException must be preserved as the cause");
    }
}
