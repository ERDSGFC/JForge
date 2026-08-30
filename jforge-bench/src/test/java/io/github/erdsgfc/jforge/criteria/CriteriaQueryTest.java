package io.github.erdsgfc.jforge.criteria;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.JForge;
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
        config.setJdbcUrl("jdbc:h2:mem:orm_criteria;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS criteria_users");
            st.execute("CREATE TABLE criteria_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT," +
                    "city VARCHAR(100)," +
                    "street VARCHAR(100))");
            st.execute("INSERT INTO criteria_users (user_name, age, city, street) VALUES " +
                    "('qin', 25, 'beijing', 'wangfujing')," +
                    "('lu', 10, 'shanghai', 'nanjing')," +
                    "('wang', 30, 'beijing', 'zhongguancun')");
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
}
