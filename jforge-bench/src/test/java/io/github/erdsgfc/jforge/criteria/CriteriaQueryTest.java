package io.github.erdsgfc.jforge.criteria;

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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 条件对象（@Where 分组/嵌套括号）与 Optional IS NULL 的集成测试。 */
class CriteriaQueryTest {

    private HikariDataSource ds;
    private CriteriaRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_criteria;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS criteria_users");
            st.execute("CREATE TABLE criteria_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT," +
                    "city VARCHAR(100)," +
                    "street VARCHAR(100)," +
                    "score INT)");
            st.execute("INSERT INTO criteria_users (user_name, age, city, street, score) VALUES " +
                    "('qin', 25, 'beijing', 'wangfujing', 100)," +
                    "('lu', 10, 'shanghai', 'nanjing', 200)," +
                    "('wang', 30, 'beijing', 'zhongguancun', 300)");
        }
        repo = new JForge(ds).repository(CriteriaRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** 空条件对象：全部字段 null → 无 WHERE，查全表。 */
    @Test
    void emptyCriteriaFullScan() {
        List<CriteriaUser> all = repo.findComplex(new UserCriteria());

        assertEquals(3, all.size());
    }

    @Test
    void whereValueFalseAllowsUnmarkedCriteriaType() {
        assertEquals(1, repo.findByLoose(new LooseCriteria("qin")).size());
    }

    /** 条件对象集合字段：IN 条件（非空命中 / 空集合 → 1 = 0 无匹配）。 */
    @Test
    void collectionFieldGeneratesIn() {
        UserCriteria criteria = new UserCriteria();
        criteria.ages = java.util.List.of(25, 30);   // age IN (25, 30)

        List<CriteriaUser> users = repo.findComplex(criteria);
        assertEquals(2, users.size());

        criteria.ages = java.util.List.of();          // 空集合 → 1 = 0
        assertTrue(repo.findComplex(criteria).isEmpty());
    }

    /** 值字段条件（null 跳过 + 连接符）。 */
    @Test
    void valueFieldsWithConnectors() {
        UserCriteria criteria = new UserCriteria();
        criteria.name = "qin";
        criteria.age = 20;   // @Or → OR age > 20

        List<CriteriaUser> users = repo.findComplex(criteria);

        // WHERE user_name = ? OR age > ?（qin 命中 name，wang 命中 age）
        assertEquals(2, users.size());
        assertTrue(users.stream().allMatch(u -> u.name().equals("qin") || u.age() > 20));
    }

    /** Optional 空 → IS NULL。 */
    @Test
    void optionalEmptyMeansIsNull() throws SQLException {
        // 造一行 user_name 为 NULL 的数据
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO criteria_users (user_name, age, city, street) VALUES (NULL, 40, 'beijing', 'x')");
        }
        UserCriteria criteria = new UserCriteria();
        criteria.nickname = Optional.empty();

        List<CriteriaUser> users = repo.findComplex(criteria);

        assertEquals(1, users.size());
        assertEquals(40, users.get(0).age());
    }

    /** Optional 有值 → 等值条件。 */
    @Test
    void optionalPresentMeansEquals() {
        UserCriteria criteria = new UserCriteria();
        criteria.nickname = Optional.of("qin");

        List<CriteriaUser> users = repo.findComplex(criteria);

        assertEquals(1, users.size());
        assertEquals("qin", users.get(0).name());
    }

    private CriteriaUser byName(String name) {
        UserCriteria c = new UserCriteria();
        c.name = name;
        return repo.findComplex(c).get(0);
    }
    /** 条件对象作 @Update 载体：@UpdateSet 字段 → SET、其余 → WHERE。 */
    @Test
    void updateWithCriteriaObject() {
        CriteriaUser saved = byName("qin");

        UserUpdateCriteria update = new UserUpdateCriteria();
        update.name = "qi";            // SET user_name = ?
        update.age = 99;               // SET age = ?
        update.id = saved.id();        // WHERE id = ?

        assertEquals(1, repo.updateByCriteria(update));

        CriteriaUser found = byName("qi");
        assertEquals(99, found.age());
        assertEquals(saved.id(), found.id());
    }

    /** 条件对象 SET 动态语义：@Nullable age 为 null → 跳过该 SET(仅 SET name)。 */
    @Test
    void updateWithCriteriaObjectSkipsNullSet() {
        CriteriaUser saved = byName("qin");
        int originalAge = saved.age();

        UserUpdateCriteria update = new UserUpdateCriteria();
        update.name = "qi";
        update.id = saved.id();        // age 留 null → SET 跳过

        assertEquals(1, repo.updateByCriteria(update));

        CriteriaUser found = byName("qi");
        assertEquals(originalAge, found.age(), "null @UpdateSet field must be skipped");
    }

    /** 条件对象 Optional SET：空 → SET score = NULL。 */
    @Test
    void updateWithCriteriaObjectOptionalEmptySetsNull() {
        CriteriaUser saved = byName("qin");
        assertTrue(saved.score() != null);

        UserUpdateCriteria update = new UserUpdateCriteria();
        update.name = "qi";
        update.score = java.util.Optional.empty();   // → SET score = NULL
        update.id = saved.id();

        assertEquals(1, repo.updateByCriteria(update));

        CriteriaUser found = byName("qi");
        assertTrue(found.score() == null, "empty Optional must SET NULL");
    }

    /** 嵌套组非 null 但组内全 null → 空组回退(不产生空括号,条件整体跳过)。 */
    @Test
    void emptyNestedGroupRollsBack() {
        UserCriteria criteria = new UserCriteria();
        criteria.address = new AddressCriteria();   // 组对象非 null,但 city/street 都 null

        List<CriteriaUser> users = repo.findComplex(criteria);

        assertEquals(3, users.size(), "empty group must roll back to no WHERE (full scan)");
    }

    /** 嵌套自定义类字段 → 括号分组。 */
    @Test
    void nestedGroupBecomesParentheses() {
        UserCriteria criteria = new UserCriteria();
        criteria.name = "qin";
        criteria.address = new AddressCriteria();
        criteria.address.city = "beijing";
        criteria.address.street = "wangfujing";

        List<CriteriaUser> users = repo.findComplex(criteria);

        // WHERE user_name = ? AND (city = ? AND street = ?)
        assertEquals(1, users.size());
        assertEquals("qin", users.get(0).name());
        assertEquals("wangfujing", users.get(0).street());
    }

    /** 嵌套组为 null → 整个括号跳过。 */
    @Test
    void nullNestedGroupSkipped() {
        UserCriteria criteria = new UserCriteria();
        criteria.name = "lu";

        List<CriteriaUser> users = repo.findComplex(criteria);

        assertEquals(1, users.size());
        assertEquals("lu", users.get(0).name());
    }

    /** @Select 参数本身是 Optional：isEmpty → IS NULL；有值 → 等值。 */
    @Test
    void optionalParameterIsNullSemantics() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO criteria_users (user_name, age, city, street) VALUES (NULL, 40, 'beijing', 'x')");
        }
        List<CriteriaUser> nullNames = repo.findByNickname(Optional.empty());
        assertEquals(1, nullNames.size());
        assertEquals(40, nullNames.get(0).age());

        List<CriteriaUser> named = repo.findByNickname(Optional.of("qin"));
        assertEquals(1, named.size());
        assertEquals("qin", named.get(0).name());
    }

    // ---- @Update / @UpdateSet 声明式更新 ----

    /** 基本更新：SET 列 + WHERE 条件（全静态 → SQL 常量）。 */
    @Test
    void updateSetAndWhere() {
        int updated = repo.updateName("renamed", 1L);

        assertEquals(1, updated);
        CriteriaUser user = repo.findById(1L);
        assertEquals("renamed", user.name());
    }

    /** @Nullable SET 参数为 null → 跳过该 SET（只更新非 null 列）。 */
    @Test
    void updateSkipsNullSet() {
        repo.updateNameAndAge("renamed", null, 1L);
        CriteriaUser user = repo.findById(1L);
        assertEquals("renamed", user.name());
        assertEquals(25, user.age(), "null SET parameter must be skipped, keeping the original value");

        repo.updateNameAndAge("renamed2", 30, 1L);
        CriteriaUser user2 = repo.findById(1L);
        assertEquals("renamed2", user2.name());
        assertEquals(30, user2.age());
    }

    /** Optional SET 空 → SET 列 = NULL。 */
    @Test
    void updateOptionalEmptySetsNull() {
        repo.updateNickname(Optional.empty(), 1L);
        CriteriaUser user = repo.findById(1L);
        assertEquals(null, user.name(), "empty Optional must SET the column to NULL");

        repo.updateNickname(Optional.of("qin"), 1L);
        CriteriaUser user2 = repo.findById(1L);
        assertEquals("qin", user2.name());
    }

    /** WHERE 用条件对象（@Where）：嵌套分组在 UPDATE 中同样生效。 */
    @Test
    void updateWithCriteriaWhere() {
        UserCriteria criteria = new UserCriteria();
        criteria.address = new AddressCriteria();
        criteria.address.city = "beijing";

        int updated = repo.updateByCriteria("all-beijing", criteria);

        assertEquals(2, updated, "two rows in beijing should be updated");
        assertTrue(repo.findByNickname(Optional.of("all-beijing")).size() >= 2);
    }

    // ---- @Delete 声明式删除 ----

    /** 基本删除：WHERE 条件（全静态 → SQL 常量）。 */
    @Test
    void deleteByCondition() {
        int deleted = repo.deleteByIdCondition(1L);

        assertEquals(1, deleted);
        assertEquals(2, repo.findAll().size());
    }

    /** Optional WHERE：isEmpty → IS NULL（删除空名行）。 */
    @Test
    void deleteByOptionalIsNull() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO criteria_users (user_name, age, city, street) VALUES (NULL, 40, 'beijing', 'x')");
        }
        int deleted = repo.deleteByNickname(Optional.empty());

        assertEquals(1, deleted);
        assertEquals(3, repo.findAll().size());
    }

    /** 条件对象 WHERE：嵌套分组删除。 */
    @Test
    void deleteByCriteriaWhere() {
        UserCriteria criteria = new UserCriteria();
        criteria.address = new AddressCriteria();
        criteria.address.city = "beijing";

        int deleted = repo.deleteByCriteria(criteria);

        assertEquals(2, deleted, "two rows in beijing should be deleted");
        assertEquals(1, repo.findAll().size());
    }

    // ---- rawSql 原生 SQL ----

    /** @Select rawSql 常量条件：WHERE age > 20（参数不绑定）。 */
    @Test
    void selectRawSqlConstant() {
        List<CriteriaUser> users = repo.findOlderThanRaw(0);

        assertEquals(2, users.size());
        assertTrue(users.stream().allMatch(u -> u.age() > 20));
    }

    /** @Update rawSql SET 表达式：score = score + ?。 */
    @Test
    void updateRawSqlSetExpression() {
        int updated = repo.incrementScore(5, 1L);

        assertEquals(1, updated);
        assertEquals(105, repo.findById(1L).score(), "score should be incremented by 5");
    }

    /** @Delete rawSql 条件。 */
    @Test
    void deleteRawSqlCondition() {
        int deleted = repo.deleteOldRaw(0);

        assertEquals(1, deleted, "one row with age > 25 should be deleted");
        assertEquals(2, repo.findAll().size());
    }

    /** 条件对象字段 rawSql：非 null 时拼常量条件。 */
    @Test
    void criteriaRawSqlField() {
        UserCriteria criteria = new UserCriteria();
        criteria.adult = 1;

        List<CriteriaUser> users = repo.findComplex(criteria);

        assertEquals(2, users.size(), "age > 18 matches qin and wang");
    }
}
