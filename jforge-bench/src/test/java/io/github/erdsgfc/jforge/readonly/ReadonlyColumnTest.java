package io.github.erdsgfc.jforge.readonly;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 列写入策略（WritePolicy）与 default 默认值的集成测试。 */
class ReadonlyColumnTest {

    private HikariDataSource ds;
    private StampUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_readonly_column;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS stamp_users");
            st.execute("CREATE TABLE stamp_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "inserted_at TIMESTAMP," +
                    "updated_at TIMESTAMP," +
                    "last_seen_at TIMESTAMP)");
        }
        repo = new JForge(ds).repository(StampUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** save：纯只读列由 DB 默认值填充；INSERT_ONLY 与 BOTH 列调用 default 绑定；UPDATE_ONLY 列不在 INSERT。 */
    @Test
    void saveRespectsWritePolicy() {
        StampUser user = repo.createEntity().name("qin").age(25);

        repo.save(user);

        StampUser found = repo.findById(user.id());
        assertNotNull(found.createdAt(), "pure read-only column filled by DB default");
        assertNotNull(found.insertedAt(), "INSERT_ONLY column filled by default method on save");
        assertNotNull(found.updatedAt(), "BOTH column filled by default method on save");
        assertNull(found.lastSeenAt(), "UPDATE_ONLY column must not be in INSERT");
    }

    /** update：纯只读与 INSERT_ONLY 列不变；BOTH 与 UPDATE_ONLY 列被 default 刷新。 */
    @Test
    void updateRespectsWritePolicy() {
        StampUser user = repo.createEntity().name("qin").age(25);
        repo.save(user);
        StampUser original = repo.findById(user.id());

        StampUser update = repo.createEntity().id(user.id()).name("renamed").age(26);
        repo.update(update);

        StampUser after = repo.findById(user.id());
        assertEquals("renamed", after.name());
        assertEquals(26, after.age());
        assertEquals(original.createdAt(), after.createdAt(), "pure read-only column untouched by update");
        assertEquals(original.insertedAt(), after.insertedAt(), "INSERT_ONLY column untouched by update");
        assertTrue(after.updatedAt().isAfter(original.updatedAt()), "BOTH column refreshed on update");
        assertNotNull(after.lastSeenAt(), "UPDATE_ONLY column filled on update");
        assertTrue(after.lastSeenAt().isAfter(original.updatedAt()),
                "UPDATE_ONLY column value should be newer than the value written on save");
    }
}
