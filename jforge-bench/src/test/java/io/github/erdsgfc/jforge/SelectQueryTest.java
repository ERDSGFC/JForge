package io.github.erdsgfc.jforge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code @Select} 声明式查询（动态 WHERE）的集成测试。 */
class SelectQueryTest {

    private HikariDataSource ds;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_select;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
            st.execute("INSERT INTO users (user_name, age) VALUES ('qin', 25), ('lu', 10), ('wang', 30)");
        }
        repo = new JForge(ds).repository(UserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** 静态条件（默认等于）。 */
    @Test
    void staticConditionEquals() {
        List<UserEntity> users = repo.findByName("qin");

        assertEquals(1, users.size());
        assertEquals("qin", users.get(0).name());
        assertEquals(25, users.get(0).age());
    }

    /** @Nullable 参数：null 时跳过条件查全表。 */
    @Test
    void nullableConditionSkippedWhenNull() {
        List<UserEntity> all = repo.findByAge(null);

        assertEquals(3, all.size(), "null condition must be skipped (full scan)");
    }

    /** @Nullable 参数：非 null 时拼接条件过滤。 */
    @Test
    void nullableConditionAppliedWhenPresent() {
        List<UserEntity> adults = repo.findByAge(30);

        assertEquals(1, adults.size());
        assertEquals("wang", adults.get(0).name());
    }

    /** 静态 + 动态混合：动态为 null 时只剩静态条件。 */
    @Test
    void mixedConditions() {
        List<UserEntity> byNameOnly = repo.findByAgeAndName(null, "qin");
        assertEquals(1, byNameOnly.size());
        assertEquals("qin", byNameOnly.get(0).name());

        List<UserEntity> both = repo.findByAgeAndName(25, "qin");
        assertEquals(1, both.size());
        assertEquals("qin", both.get(0).name());

        List<UserEntity> none = repo.findByAgeAndName(99, "nobody");
        assertTrue(none.isEmpty());
    }

    /** 操作符：GT。 */
    @Test
    void operatorGreaterThan() {
        List<UserEntity> adults = repo.findOlderThan(20);

        assertEquals(2, adults.size());
        assertTrue(adults.stream().allMatch(u -> u.age() > 20));
    }

    /** 操作符：LIKE。 */
    @Test
    void operatorLike() {
        List<UserEntity> users = repo.findByNameLike("q%");

        assertEquals(1, users.size());
        assertEquals("qin", users.get(0).name());
    }

    /** 标量返回：COUNT(*)。 */
    @Test
    void scalarCount() {
        assertEquals(1, repo.countByName("qin"));
        assertEquals(0, repo.countByName("nobody"));
    }

    /** record 投影：组件名经命名策略映射列。 */
    @Test
    void recordProjection() {
        UserEntity qin = repo.findByName("qin").get(0);

        List<UserNameDto> dtos = repo.findNameDtoById(qin.id());

        assertEquals(1, dtos.size());
        assertEquals(qin.id(), dtos.get(0).id());
        assertEquals("qin", dtos.get(0).user_name());
    }

    /** 无参数 @Select：全表。 */
    @Test
    void noParametersFullScan() {
        List<UserEntity> all = repo.findAllUsers();

        assertEquals(3, all.size());
    }

    /** @Select 与 @Query 共存：原有 @Query 不受影响。 */
    @Test
    void coexistsWithQuery() {
        List<UserEntity> adults = repo.findByAgeGreaterThan(20);
        assertEquals(2, adults.size());
        assertEquals(1, repo.countByAge(25));
    }

    /** 动态查询返回的实体 id 完整映射（行映射复用）。 */
    @Test
    void mappedEntityHasId() {
        List<UserEntity> users = repo.findByAge(null);

        assertNotNull(users.get(0).id());
        assertEquals(1L, users.get(0).id());
    }
}
