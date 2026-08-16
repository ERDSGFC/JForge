package io.github.erdsgfc.jforge.readonly;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 部分只读实体（只读 id + 可写字段）的集成测试：save 回写走强转到嵌套类的路径。 */
class PartialReadonlyTest {

    private HikariDataSource ds;
    private AutoIdUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_partial_readonly;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS auto_id_users");
            st.execute("CREATE TABLE auto_id_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
        repo = new JForge(ds).repository(AutoIdUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** 只读 id 的实体 save 后主键仍回写（强转到嵌套类调用私有 setter）。 */
    @Test
    void saveWritesBackGeneratedIdToReadonlyId() {
        AutoIdUser user = repo.createEntity().name("qin").age(25);   // 可写字段照常链式

        AutoIdUser saved = repo.save(user);

        assertNotNull(saved.id(), "generated id should be written back even without an interface setter");
        assertEquals("qin", saved.name());
        assertEquals(25, saved.age());
    }

    /** 批量 save 同样回写只读 id。 */
    @Test
    void saveAllWritesBackGeneratedIds() {
        List<AutoIdUser> users = new ArrayList<>();
        users.add(repo.createEntity().name("qin").age(25));
        users.add(repo.createEntity().name("lu").age(10));

        List<AutoIdUser> saved = repo.save(users);

        assertNotNull(saved.get(0).id());
        assertNotNull(saved.get(1).id());
        assertEquals("qin", saved.get(0).name());
        assertEquals("lu", saved.get(1).name());
    }

    /** 读路径完整映射（含只读 id 列）。 */
    @Test
    void findByIdMapsAllColumns() {
        AutoIdUser user = repo.createEntity().name("qin").age(25);
        repo.save(user);

        AutoIdUser found = repo.findById(user.id());

        assertNotNull(found);
        assertEquals(user.id(), found.id());
        assertEquals("qin", found.name());
        assertEquals(25, found.age());
    }
}
