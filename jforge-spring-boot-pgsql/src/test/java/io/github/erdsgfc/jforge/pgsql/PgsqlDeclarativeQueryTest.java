package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真 PG 声明式查询验证：@Select/@Update/@Delete 生成的 SQL（引用符包裹）在真库上的
 * 语义——静态/动态条件、条件对象（值/OR/Optional IS NULL/嵌套括号/rawSql 常量）、
 * record 投影、分页、@Query 动态段。
 */
class PgsqlDeclarativeQueryTest extends PgsqlTestSupport {

    private JForge jforge;
    private PgUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        createPgUsersTable();
        jforge = new JForge(dataSource());
        repo = jforge.repository(PgUserRepository.class);
        repo.save(repo.createEntity().userName("qin").age(25).order(1).city("shanghai").street("nanjing"));
        repo.save(repo.createEntity().userName("lu").age(10).order(2).city("beijing").street("wangfujing"));
        repo.save(repo.createEntity().userName("wang").age(30).order(3).city("shanghai").street("huaihai"));
    }

    /** 静态条件（SQL 常量形态）。 */
    @Test
    void staticCondition() {
        List<PgUser> found = repo.findByUserName("qin");
        assertEquals(1, found.size());
        assertEquals("qin", found.get(0).userName());
    }

    /** 动态条件：null → 全表。 */
    @Test
    void dynamicConditionNullMeansAll() {
        assertEquals(3, repo.findByAge(null).size());
        assertEquals(1, repo.findByAge(25).size());
    }

    /** 保留字列条件（{@code "order" > ?}）。 */
    @Test
    void reservedWordColumnCondition() {
        List<PgUser> found = repo.findByOrderGreaterThan(1);
        assertEquals(2, found.size());
    }

    /** record 投影（SELECT 列经命名策略 + 引用符）。 */
    @Test
    void recordProjection() {
        PgUser saved = repo.findByUserName("qin").get(0);
        List<PgUserNameDto> dtos = repo.findNameDtoById(saved.id());
        assertEquals(1, dtos.size());
        assertEquals(saved.id(), dtos.get(0).id());
        assertEquals("qin", dtos.get(0).userName());
    }

    /** 标量 COUNT(*)。 */
    @Test
    void scalarCount() {
        assertEquals(1, repo.countByUserName("qin"));
        assertEquals(0, repo.countByUserName("nobody"));
    }

    /** 条件对象：值条件 + OR + Optional 空 → IS NULL + 嵌套括号 + rawSql 常量。 */
    @Test
    void criteriaObject() {
        PgUserCriteria criteria = new PgUserCriteria();
        criteria.userName = "qin";
        criteria.age = 20;   // OR age > 20（25 命中）
        criteria.nickname = Optional.empty();   // user_name IS NULL——但 userName 已匹配，IS NULL 不命中
        criteria.address = new PgAddressCriteria();
        criteria.address.city = "shanghai";
        criteria.address.street = "nanjing";   // (city = ? AND street = ?)
        criteria.adult = 1;   // rawSql 常量 age > 18

        List<PgUser> found = repo.findByCriteria(criteria);
        assertEquals(1, found.size());
        assertEquals("qin", found.get(0).userName());
    }

    /** Optional 参数：空 → IS NULL（显式空值查询）。 */
    @Test
    void optionalEmptyMeansIsNull() {
        repo.save(repo.createEntity().userName(null).age(5));
        assertEquals(4, repo.count());

        List<PgUser> found = repo.findByNickname(Optional.empty());
        assertEquals(1, found.size());
        assertTrue(found.get(0).userName() == null);
    }

    /** Optional 参数：有值 → 等于条件。 */
    @Test
    void optionalPresentMeansEquals() {
        List<PgUser> found = repo.findByNickname(Optional.of("lu"));
        assertEquals(1, found.size());
        assertEquals("lu", found.get(0).userName());
    }

    /** 动态 SET：null 跳过该列。 */
    @Test
    void updateSkipsNullSet() {
        PgUser saved = repo.findByUserName("qin").get(0);

        repo.updateAgeById(null, saved.id());
        assertEquals(25, repo.findById(saved.id()).age(), "null @UpdateSet must skip the column");

        repo.updateAgeById(88, saved.id());
        assertEquals(88, repo.findById(saved.id()).age());
    }

    /** Optional SET：空 → SET NULL。 */
    @Test
    void updateOptionalEmptySetsNull() {
        PgUser saved = repo.findByUserName("qin").get(0);

        repo.updateNickname(Optional.of("xiaoqin"), saved.id());
        assertEquals("xiaoqin", repo.findById(saved.id()).userName());

        repo.updateNickname(Optional.empty(), saved.id());
        assertTrue(repo.findById(saved.id()).userName() == null, "empty Optional must SET NULL");
    }

    /** 条件对象删除。 */
    @Test
    void deleteByCriteria() {
        PgUserCriteria criteria = new PgUserCriteria();
        criteria.age = 29;   // age > 29 → wang 命中

        int deleted = repo.deleteByCriteria(criteria);
        assertEquals(1, deleted);
        assertEquals(2, repo.count());
    }

    /** 普通参数条件删除。 */
    @Test
    void deleteByUserName() {
        int deleted = repo.deleteByUserName("lu");
        assertEquals(1, deleted);
        assertEquals(2, repo.count());
    }

    /** @Query 分页（PG LIMIT/OFFSET）。 */
    @Test
    void pagedQuery() {
        List<PgUser> page = repo.pageByAge(0, 2, 0);
        assertEquals(2, page.size());
        assertEquals("qin", page.get(0).userName());
        assertEquals("lu", page.get(1).userName());

        List<PgUser> page2 = repo.pageByAge(0, 2, 2);
        assertEquals(1, page2.size());
        assertEquals("wang", page2.get(0).userName());
    }

    /** @Query 方括号动态段：null 跳过。 */
    @Test
    void dynamicQuerySegment() {
        assertEquals(1, repo.findDynamicByAgeAndName(null, "qin").size());
        assertEquals(1, repo.findDynamicByAgeAndName(25, "qin").size());
        assertEquals(0, repo.findDynamicByAgeAndName(30, "qin").size());
    }

    /** 无条件的 @Select 全表。 */
    @Test
    void noConditionFullScan() {
        assertNotNull(repo.findByAge(null));
        assertEquals(3, repo.findByAge(null).size());
    }
}
