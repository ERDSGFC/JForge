package io.github.erdsgfc.jforge.readonly;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 只读实体（接口只有 getter）的集成测试：行映射经私有填充 setter 正常工作。 */
class ReadonlyEntityTest {

    private HikariDataSource ds;
    private ReadonlyUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_readonly;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS readonly_users");
            st.execute("CREATE TABLE readonly_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
            // 只读实体无法经 setter 构造，用原生 JDBC 预置数据。
            st.execute("INSERT INTO readonly_users (user_name, age) VALUES ('qin', 25), ('lu', 10)");
        }
        repo = new JForge(ds).repository(ReadonlyUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void findByIdMapsReadonlyEntity() {
        ReadonlyUser user = repo.findById(1L);

        assertNotNull(user);
        assertEquals(1L, user.id());
        assertEquals("qin", user.name());
        assertEquals(25, user.age());
    }

    @Test
    void findAllMapsReadonlyEntity() {
        List<ReadonlyUser> all = repo.findAll();

        assertEquals(2, all.size());
        assertEquals("qin", all.get(0).name());
        assertEquals("lu", all.get(1).name());
    }

    @Test
    void queryMapsReadonlyEntity() {
        List<ReadonlyUser> adults = repo.findByAgeGreaterThan(20);

        assertEquals(1, adults.size());
        assertEquals("qin", adults.get(0).name());
        assertEquals(25, adults.get(0).age());
    }
}
