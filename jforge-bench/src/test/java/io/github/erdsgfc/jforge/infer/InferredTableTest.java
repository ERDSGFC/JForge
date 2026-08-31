package io.github.erdsgfc.jforge.infer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证无 {@code @Table} 实体的表名推断:类名 {@code InferredTableEntity} →
 * 表名 {@code inferred_table_entity},CRUD 全链路使用推断表名。
 */
class InferredTableTest {

    private HikariDataSource ds;
    private InferredTableRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_infer;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS inferred_table_entity");
            st.execute("CREATE TABLE inferred_table_entity (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "name VARCHAR(100))");
        }
        repo = new JForge(ds).repository(InferredTableRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void crudWorksOnInferredTableName() {
        InferredTableEntity saved = repo.save(repo.createEntity().name("inferred").id(null));

        assertNotNull(saved.id(), "generated id must be written back");
        assertEquals(1, repo.count(), "insert/find must hit the inferred table name");
        assertEquals("inferred", repo.findById(saved.id()).name());

        repo.deleteById(saved.id());
        assertEquals(0, repo.count());
    }
}
