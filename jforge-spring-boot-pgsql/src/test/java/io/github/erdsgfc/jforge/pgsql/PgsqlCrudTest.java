package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 真 PG CRUD 全链路：批量生成键回写（JDBC 标准 getGeneratedKeys 路径）、
 * 保留字列 {@code "order"} 的引用符精确匹配、update/delete round-trip。
 */
class PgsqlCrudTest extends PgsqlTestSupport {

    private JForge jforge;
    private PgUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        createTable("pg_users", "id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100), age INT, "
                + "\"order\" INT, city VARCHAR(100), street VARCHAR(100), created_at TIMESTAMP");
        jforge = new JForge(dataSource());
        repo = jforge.repository(PgUserRepository.class);
    }

    /** saveAll 批量：单连接 + 批量生成键按插入序回写（PG 驱动 getGeneratedKeys 路径）。 */
    @Test
    void saveAllWritesBackBatchGeneratedKeysInOrder() {
        List<PgUser> users = new ArrayList<>();
        users.add(repo.createEntity().userName("qin").age(25));
        users.add(repo.createEntity().userName("lu").age(10));
        users.add(repo.createEntity().userName("wang").age(30));

        repo.save(users);

        for (int i = 0; i < users.size(); i++) {
            PgUser saved = users.get(i);
            assertNotNull(saved.id(), "batch generated key must be written back in order for row " + i);
            PgUser found = repo.findById(saved.id());
            assertNotNull(found);
            assertEquals(saved.userName(), found.userName());
        }
        assertEquals(3, repo.count());
    }

    /** 保留字列全链路：save → 条件查询 → 更新 → 删除（引用符包裹在真库上工作）。 */
    @Test
    void quotedReservedWordColumnFullChain() {
        PgUser saved = repo.save(repo.createEntity().userName("qin").age(25).order(5));

        List<PgUser> found = repo.findByOrderGreaterThan(3);
        assertEquals(1, found.size());
        assertEquals(5, found.get(0).order());

        repo.updateNameAndOrder("qi", 9, saved.id());
        PgUser updated = repo.findById(saved.id());
        assertEquals("qi", updated.userName());
        assertEquals(9, updated.order());

        int deleted = repo.deleteByUserName("qi");
        assertEquals(1, deleted);
        assertNull(repo.findById(saved.id()));
    }

    /** update/delete round-trip：影响行数与状态一致。 */
    @Test
    void updateAndDeleteRoundTrip() {
        PgUser a = repo.save(repo.createEntity().userName("a").age(1));
        PgUser b = repo.save(repo.createEntity().userName("b").age(2));

        int updated = repo.updateAgeById(99, a.id());
        assertEquals(1, updated);
        assertEquals(99, repo.findById(a.id()).age());
        assertEquals(2, repo.findById(b.id()).age(), "other row must be untouched");

        assertEquals(2, repo.count());
        int deleted = repo.deleteByUserName("a");
        assertEquals(1, deleted);
        assertEquals(1, repo.count());
    }

    /** findById 映射全列（含保留字列与 default 列）。 */
    @Test
    void findByIdMapsAllColumns() {
        PgUser saved = repo.save(repo.createEntity().userName("qin").age(25).order(7)
                .city("shanghai").street("nanjing"));

        PgUser found = repo.findById(saved.id());

        assertNotNull(found);
        assertEquals(saved.id(), found.id());
        assertEquals("qin", found.userName());
        assertEquals(25, found.age());
        assertEquals(7, found.order());
        assertEquals("shanghai", found.city());
        assertEquals("nanjing", found.street());
        assertNotNull(found.createdAt());
    }
}
