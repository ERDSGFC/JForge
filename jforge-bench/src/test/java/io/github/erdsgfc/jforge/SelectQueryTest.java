package io.github.erdsgfc.jforge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

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
        config.setJdbcUrl("jdbc:h2:mem:orm_select;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
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

    // ---- 动态判定矩阵用例 ----

    /** 基本类型参数：静态形态（过滤正确）。 */
    @Test
    void primitiveStaticForm() {
        List<UserEntity> adults = repo.findAdults(25);

        assertEquals(1, adults.size());
        assertEquals("qin", adults.get(0).name());
    }

    /** 未标注 boxed 参数位于非 {@code @NullMarked} 作用域：默认可空并动态处理 null。 */
    @Test
    void boxedUnannotatedNullableForm() {
        List<UserEntity> found = repo.findByBoxedAge(25);

        assertEquals(1, found.size());
        assertEquals("qin", found.get(0).name());
        assertEquals(3, repo.findByBoxedAge(null).size());
    }

    @Test
    void queryAutoParameterWhere() {
        assertEquals(1, repo.findByAutoQueryParameter("qin").size());
    }

    @Test
    void iterableAndArrayConditionsUseIn() {
        assertEquals(2, repo.findByIdIn(List.of(1L, 2L)).size());
        assertEquals(2, repo.findByIdArray(new long[]{1L, 2L}).size());
        assertTrue(repo.findByIdIn(List.of()).isEmpty());
    }

    @Test
    void notInConditionsUseNotInAndEmptyIsTrue() {
        assertEquals(1, repo.findByIdNotIn(List.of(1L, 2L)).size());
        assertEquals(3, repo.findByIdNotIn(List.of()).size());
        assertEquals(1, repo.findByIdNotInArray(new long[]{1L, 2L}).size());
        assertEquals(3, repo.findByIdNotInArray(new long[]{}).size());
    }

    /** 无 {@code @NullMarked} 时，未标注实体引用类型生成 {@code @Nullable}。 */
    @Test
    void unmarkedEntityTypesAreGeneratedNullable() throws ReflectiveOperationException {
        Class<?> entityImpl = Class.forName(
                "io.github.erdsgfc.jforge.UserRepository_Impl$UserEntity_Impl");

        assertNotNull(entityImpl.getDeclaredField("name").getAnnotatedType().getAnnotation(Nullable.class));
        assertNotNull(entityImpl.getDeclaredField("age").getAnnotatedType().getAnnotation(Nullable.class));
        assertNotNull(entityImpl.getDeclaredMethod("name").getAnnotatedReturnType()
                .getAnnotation(Nullable.class));
        assertNotNull(entityImpl.getDeclaredMethod("age").getAnnotatedReturnType()
                .getAnnotation(Nullable.class));
        assertNotNull(entityImpl.getDeclaredMethod("name", String.class)
                .getAnnotatedParameterTypes()[0].getAnnotation(Nullable.class));
    }

    /** Optional + @Nullable 双层：null → 跳过整个条件；empty → IS NULL；有值 → 等于。 */
    @Test
    void optionalNullableDoubleGuard() {
        assertEquals(1, repo.findByNicknameNullable(Optional.of("lu")).size());

        // name 列为 NULL 的行（findByNicknameNullable 映射 name 字段）→ IS NULL 命中。
        repo.save(repo.createEntity().name(null).age(99));
        List<UserEntity> nulls = repo.findByNicknameNullable(Optional.empty());
        assertEquals(1, nulls.size());
        assertEquals(99, nulls.get(0).age());

        // Optional 参数本身为 null（@Nullable 守卫）→ 跳过整个条件查全表。
        assertEquals(4, repo.findByNicknameNullable(null).size());
    }

    /** 双动态条件四组合：双值区间 / 单上限 / 单下限 / 全 null 无 WHERE。 */
    @Test
    void dualDynamicRangeCombinations() {
        assertEquals(1, repo.findByAgeRange(20, 30).size());        // 25
        assertEquals(2, repo.findByAgeRange(null, 30).size());      // 25, 10
        assertEquals(2, repo.findByAgeRange(20, null).size());      // 25, 30
        assertEquals(3, repo.findByAgeRange(null, null).size());    // 全表
    }

    /** 全参数动态：全部 null → 无 WHERE 查全表；单条件各一。 */
    @Test
    void allNullMeansFullScan() {
        assertEquals(3, repo.findByAgeOrName(null, null).size());
        assertEquals(1, repo.findByAgeOrName(25, null).size());
        assertEquals(1, repo.findByAgeOrName(null, "qin").size());
        assertEquals(0, repo.findByAgeOrName(99, null).size());
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

    @Test
    void queryWithAppendedNotInCollection() {
        assertEquals(1, repo.findByQueryNotIn(List.of(1L, 2L)).size());
        assertEquals(3, repo.findByQueryNotIn(List.of()).size());
        assertEquals(3, repo.findByQueryNotIn(null).size());
        assertEquals(1, repo.findByQueryNotInArray(new long[]{1L, 2L}).size());
        assertEquals(3, repo.findByQueryNotInArray(new long[]{}).size());
    }
}
