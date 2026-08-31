package io.github.erdsgfc.jforge.nullable;

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
import static org.junit.jupiter.api.Assertions.assertNull;

/** 列空性（wasNull 映射）的集成测试：可空列 NULL 读回 null，基本类型读 0。 */
class NullableColumnTest {

    private HikariDataSource ds;
    private NullableUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_nullable;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS nullable_users");
            st.execute("CREATE TABLE nullable_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "name VARCHAR(100)," +
                    "nickname VARCHAR(100)," +
                    "age INT," +
                    "score INT)");
        }
        repo = new JForge(ds).repository(NullableUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** 全 NULL 行：可空列读回 null（标注/全局默认/包装类三条路径），基本类型读 0。 */
    @Test
    void nullRowMapsNullableColumnsToNull() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO nullable_users (name, nickname, age, score) VALUES (NULL, NULL, NULL, NULL)");
        }

        NullableUser user = repo.findById(1L);

        assertNull(user.name(), "@Nullable annotated String column should read back null");
        assertNull(user.nickname(), "unannotated String column should use global columnsNullable default");
        assertEquals(0, user.age(), "primitive int column has no wasNull branch — NULL reads 0");
        assertNull(user.score(), "boxed Integer column should read back null");
    }

    /** 非 NULL 行：可空列正常读回，wasNull 分支不影响非 null 值。 */
    @Test
    void nonNullRowReadsValuesNormally() {
        NullableUser user = repo.createEntity();
        user.name("qin");
        user.nickname("qq");
        user.age(25);
        user.score(88);
        repo.save(user);

        NullableUser found = repo.findById(user.id());

        assertEquals("qin", found.name());
        assertEquals("qq", found.nickname());
        assertEquals(25, found.age());
        assertEquals(88, found.score());
    }
}
