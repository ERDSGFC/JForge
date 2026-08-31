package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        createPgUsersTable();
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

    /** 全字段类型 round-trip：BOOLEAN/NUMERIC/DATE/DOUBLE/REAL/SMALLINT/BYTEA/PG 枚举。 */
    @Test
    void allTypesRoundTrip() {
        byte[] avatar = {1, 2, 3, -4, 5};
        PgUser saved = repo.save(repo.createEntity()
                .userName("types").age(30).order(1)
                .active(true)
                .balance(new java.math.BigDecimal("1234.56"))
                .birthDate(java.time.LocalDate.of(2000, 1, 2))
                .height(1.75)
                .weight(65.5f)
                .level((short) 3)
                .avatar(avatar)
                .status(PgUserStatus.ACTIVE));

        PgUser found = repo.findById(saved.id());
        assertNotNull(found);
        assertEquals(Boolean.TRUE, found.active());
        assertEquals(new java.math.BigDecimal("1234.56"), found.balance());
        assertEquals(java.time.LocalDate.of(2000, 1, 2), found.birthDate());
        assertEquals(1.75, found.height(), 1e-9);
        assertEquals(65.5f, found.weight(), 1e-6f);
        assertEquals((short) 3, found.level());
        assertArrayEquals(avatar, found.avatar());
        assertEquals(PgUserStatus.ACTIVE, found.status());
    }

    /** 全类型列留 null 时读回 null（可空 boxed 走 wasNull、String 等天然 null）。 */
    @Test
    void allTypesNullRoundTrip() {
        PgUser saved = repo.save(repo.createEntity().userName("nulls").age(1));

        PgUser found = repo.findById(saved.id());

        assertNotNull(found);
        assertNull(found.active(), "Boolean null must read back as null (wasNull path)");
        assertNull(found.balance());
        assertNull(found.birthDate());
        assertNull(found.height());
        assertNull(found.weight());
        assertNull(found.level());
        assertNull(found.avatar());
        assertNull(found.status());
        assertNull(found.externalId());
    }

    /** @Convert 自定义转换器：UUID ↔ VARCHAR 文本在真 PG 上的绑定与读取。 */
    @Test
    void convertRoundTrip() throws SQLException {
        java.util.UUID externalId = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        PgUser saved = repo.save(repo.createEntity().userName("conv").age(1).externalId(externalId));

        PgUser found = repo.findById(saved.id());
        assertNotNull(found);
        assertEquals(externalId, found.externalId());

        // 数据库实际存储转换后的 UUID 文本。
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT external_id FROM pg_users WHERE id = " + saved.id());
            rs.next();
            assertEquals("550e8400-e29b-41d4-a716-446655440000", rs.getString(1));
        }

        // null 透传。
        repo.save(repo.createEntity().userName("conv-null").age(2));
        PgUser nullFound = repo.findByUserName("conv-null").get(0);
        assertNull(nullFound.externalId());
    }
}
