package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.core.JForge;
import io.github.erdsgfc.jforge.pgsql.batch.BatchPgUser;
import io.github.erdsgfc.jforge.pgsql.batch.BatchPgUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真 PG 批处理验证：batchSize=2 分块 flush（2/2/1），批量生成键经 PG 驱动
 * {@code getGeneratedKeys} 按插入序回写全部实体。
 */
class PgsqlBatchTest extends PgsqlTestSupport {

    private JForge jforge;
    private BatchPgUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        createTable("pg_batch_users", "id BIGSERIAL PRIMARY KEY, user_name VARCHAR(100)");
        jforge = new JForge(dataSource());
        repo = jforge.repository(BatchPgUserRepository.class);
    }

    /** 5 行 → 3 次 executeBatch flush（2/2/1），全部 id 按插入序回写。 */
    @Test
    void batchSize2FlushesInChunksAndWritesBackAllIds() {
        List<BatchPgUser> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            users.add(repo.createEntity().userName("u" + i));
        }

        repo.save(users);

        assertEquals(5, repo.count());
        for (int i = 0; i < users.size(); i++) {
            BatchPgUser saved = users.get(i);
            assertNotNull(saved.id(), "batch generated key missing for row " + i);
            assertEquals("u" + i, repo.findById(saved.id()).userName(),
                    "key writeback must preserve insertion order");
        }
    }
}
