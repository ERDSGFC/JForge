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

    // ---- @Query 动态 WHERE（方括号显式 / @Nullable 自动推断）----

    /** 方括号动态段：null 时跳过，只剩静态条件。 */
    @Test
    void queryBracketedDynamicSegment() {
        List<UserEntity> byName = repo.findDynamicByAgeAndName(null, "qin");
        assertEquals(1, byName.size());
        assertEquals("qin", byName.get(0).name());

        List<UserEntity> both = repo.findDynamicByAgeAndName(25, "qin");
        assertEquals(1, both.size());
        assertEquals("qin", both.get(0).name());

        List<UserEntity> none = repo.findDynamicByAgeAndName(30, "qin");
        assertTrue(none.isEmpty());
    }

    /** @Nullable 自动推断：未用方括号的单占位符片段动态。 */
    @Test
    void queryAutoDynamicByNullable() {
        List<UserEntity> byName = repo.findAutoDynamicByAgeAndName("qin", null);
        assertEquals(1, byName.size());
        assertEquals("qin", byName.get(0).name());

        List<UserEntity> both = repo.findAutoDynamicByAgeAndName("qin", 25);
        assertEquals(1, both.size());
        assertEquals(25, both.get(0).age());

        assertTrue(repo.findAutoDynamicByAgeAndName("qin", 30).isEmpty());
    }

    /** OR 连接符保留：动态段 null 时 OR 条件消失，其余条件原样。 */
    @Test
    void queryDynamicPreservesOrConnector() {
        // age=null → WHERE user_name = ?（OR 段整体消失）
        List<UserEntity> byName = repo.findDynamicOr(null, "qin");
        assertEquals(1, byName.size());
        assertEquals("qin", byName.get(0).name());

        // age=25 → WHERE age = ? OR user_name = ?（OR 语义保留：qin 命中 age，lu 命中 name）
        List<UserEntity> byAgeOrName = repo.findDynamicOr(25, "lu");
        assertEquals(2, byAgeOrName.size());
        assertTrue(byAgeOrName.stream().allMatch(u -> u.age() == 25 || u.name().equals("lu")));
    }

    /** 动态 @Query 与静态 @Query 共存（静态路径不变）。 */
    @Test
    void dynamicQueryCoexistsWithStatic() {
        assertEquals(2, repo.findByAgeGreaterThan(20).size());
        assertEquals(1, repo.countByAge(25));
        assertEquals("qin", repo.findNameById(1L).user_name());
    }

    // ---- @Query + @Condition 追加条件 ----

    /** @Condition 动态追加：SQL 写静态条件，@Nullable 参数追加 AND 条件。 */
    @Test
    void queryWithAppendedDynamicWhere() {
        // age=null → 只按 name（追加条件跳过）
        List<UserEntity> byName = repo.findWithAppendedWhere("qin", null);
        assertEquals(1, byName.size());
        assertEquals("qin", byName.get(0).name());

        // age=20 → AND age > 20（qin/25 + wang/30）
        List<UserEntity> adults = repo.findWithAppendedWhere("qin", 20);
        assertEquals(1, adults.size());
        assertEquals("qin", adults.get(0).name());

        // age=30 → AND age > 30（无匹配）
        assertTrue(repo.findWithAppendedWhere("qin", 30).isEmpty());
    }

    /** @Condition 静态追加（无 @Nullable）：恒拼接，走 SQL 常量形态。 */
    @Test
    void queryWithAppendedStaticWhere() {
        // WHERE user_name = ? AND age >= ?
        List<UserEntity> users = repo.findWithAppendedStaticWhere("qin", 20);
        assertEquals(1, users.size());
        assertEquals("qin", users.get(0).name());

        assertTrue(repo.findWithAppendedStaticWhere("qin", 30).isEmpty());
    }
}
