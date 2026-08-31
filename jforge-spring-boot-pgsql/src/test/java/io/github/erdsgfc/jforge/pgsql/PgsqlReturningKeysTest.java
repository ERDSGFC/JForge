package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.core.JForge;
import io.github.erdsgfc.jforge.pgsql.readonly.AutoIdPgUser;
import io.github.erdsgfc.jforge.pgsql.readonly.AutoIdPgUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真 PG 生成键验证：{@code INSERT ... RETURNING}（POSTGRESQL 方言启用后首次被真实
 * 数据库执行）——save 回写、只读 id 强转回写、default 列与 RETURNING 同语句、
 * 序列递增。
 */
class PgsqlReturningKeysTest extends PgsqlTestSupport {

    private JForge jforge;

    @BeforeEach
    void setUp() throws SQLException {
        createTable("pg_users", "id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100), age INT, "
                + "\"order\" INT, city VARCHAR(100), street VARCHAR(100), created_at TIMESTAMP");
        createTable("pg_auto_id_users", "id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100), age INT");
        jforge = new JForge(dataSource());
    }

    /** RETURNING 路径：save 单语句拿生成键并回写。 */
    @Test
    void saveWritesBackReturningId() {
        PgUserRepository repo = jforge.repository(PgUserRepository.class);

        PgUser saved = repo.save(repo.createEntity().userName("qin").age(25).order(1));

        assertNotNull(saved.id(), "RETURNING must write back the generated id");
        assertTrue(saved.id() > 0);
        PgUser found = repo.findById(saved.id());
        assertNotNull(found);
        assertEquals("qin", found.userName());
        assertEquals(25, found.age());
        assertEquals(1, found.order());
    }

    /** 只读 id（无 setter）：RETURNING 回写经嵌套类强转路径。 */
    @Test
    void saveReadonlyIdWritesBackViaNestedClassCast() {
        AutoIdPgUserRepository repo = jforge.repository(AutoIdPgUserRepository.class);

        AutoIdPgUser saved = repo.save(repo.createEntity().userName("lu").age(10));

        assertNotNull(saved.id(), "readonly id must still be written back via nested-class cast");
        AutoIdPgUser found = repo.findById(saved.id());
        assertNotNull(found);
        assertEquals("lu", found.userName());
    }

    /** default getter 列（createdAt）与 RETURNING 同语句共存。 */
    @Test
    void defaultValueColumnBoundAndReturningId() {
        PgUserRepository repo = jforge.repository(PgUserRepository.class);

        PgUser saved = repo.save(repo.createEntity().userName("default").age(1));

        assertNotNull(saved.id());
        // default getter 只绑定 SQL（不初始化实体字段），DB 读回验证默认值已持久化。
        PgUser found = repo.findById(saved.id());
        assertNotNull(found.createdAt(), "default getter value must be persisted");
    }

    /** BIGSERIAL 真实序列：连续 save 的 id 递增且互异。 */
    @Test
    void consecutiveSavesReturnDistinctAscendingIds() {
        PgUserRepository repo = jforge.repository(PgUserRepository.class);

        PgUser a = repo.save(repo.createEntity().userName("a").age(1));
        PgUser b = repo.save(repo.createEntity().userName("b").age(2));

        assertTrue(a.id() < b.id(), "BIGSERIAL must produce ascending ids: " + a.id() + " vs " + b.id());
    }
}
