package io.github.erdsgfc.jforge.inherit;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实体接口继承父接口时，父接口属性参与映射的集成测试。 */
class EntityInheritanceTest {

    private HikariDataSource ds;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_inherit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "user_name VARCHAR(100)," +
                    "age INT)");
        }
        repo = new JForge(ds).repository(UserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** 父接口的 @Id @GeneratedValue 生效：save 回写生成主键。 */
    @Test
    void saveWritesBackParentInterfaceGeneratedId() {
        UserEntity user = repo.createEntity();
        user.name("qin");
        user.age(25);

        UserEntity saved = repo.save(user);

        assertNotNull(saved.id(), "parent interface @Id should drive generated-key write-back");
        assertEquals("qin", saved.name());
        assertEquals(25, saved.age());
    }

    /** 泛型父接口（CRTP）：setter 返回类型替换为子接口，链式调用跨接口不中断。 */
    @Test
    void chainedBuilderAcrossGenericParentInterface() {
        // name() 声明在父接口（返回 T），替换后为 UserEntity——可直接继续 .age(...)。
        UserEntity user = repo.createEntity().name("qin").age(25);

        UserEntity saved = repo.save(user);

        assertNotNull(saved.id());
        assertEquals("qin", saved.name());
        assertEquals(25, saved.age());
    }

    /** 父接口属性与子接口属性一起参与行映射。 */
    @Test
    void findByIdMapsParentAndChildColumns() {
        UserEntity user = repo.createEntity();
        user.name("qin");
        user.age(25);
        repo.save(user);

        UserEntity found = repo.findById(user.id());

        assertNotNull(found);
        assertEquals(user.id(), found.id());
        assertEquals("qin", found.name());
        assertEquals(25, found.age());
    }

    /** findAll 同样完整映射继承的属性。 */
    @Test
    void findAllMapsAllColumns() {
        UserEntity user = repo.createEntity();
        user.name("qin");
        user.age(25);
        repo.save(user);

        List<UserEntity> all = repo.findAll();

        assertEquals(1, all.size());
        assertEquals("qin", all.get(0).name());
        assertEquals(25, all.get(0).age());
    }

    /** @Query 返回实体：父接口属性按列名映射。 */
    @Test
    void queryMapsParentColumnsByName() {
        UserEntity user = repo.createEntity();
        user.name("qin");
        user.age(30);
        repo.save(user);
        UserEntity other = repo.createEntity();
        other.name("lu");
        other.age(10);
        repo.save(other);

        List<UserEntity> adults = repo.findByAgeGreaterThan(20);

        assertEquals(1, adults.size());
        assertEquals("qin", adults.get(0).name());
        assertEquals(30, adults.get(0).age());
    }

    /** update 使用父接口属性作为 SET 列。 */
    @Test
    void updateSetsParentColumn() {
        UserEntity user = repo.createEntity();
        user.name("qin");
        user.age(25);
        repo.save(user);

        UserEntity update = repo.createEntity();
        update.id(user.id());
        update.name("renamed");
        update.age(26);
        boolean updated = repo.update(update);

        assertTrue(updated);
        UserEntity found = repo.findById(user.id());
        assertEquals("renamed", found.name());
        assertEquals(26, found.age());
    }
}
