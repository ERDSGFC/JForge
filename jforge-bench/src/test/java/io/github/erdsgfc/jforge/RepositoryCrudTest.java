package io.github.erdsgfc.jforge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for the generated repository implementation. */
class RepositoryCrudTest {

    private HikariDataSource ds;
    private UserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_repo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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

    @Test
    void saveWritesBackGeneratedId() {
        UserEntity user = repo.createEntity()
                .name("qin")
                .age(25);

        UserEntity saved = repo.save(user);

        assertNotNull(saved.id(), "generated id should be written back");
        assertEquals("qin", saved.name());
        assertEquals(25, saved.age());
    }

    @Test
    void findByIdAndUpdate() {
        UserEntity user = repo.createEntity().name("qin").age(25);
        repo.save(user);

        UserEntity found = repo.findById(user.id());
        assertNotNull(found);
        assertEquals("qin", found.name());

        boolean updated = repo.update(repo.createEntity().id(user.id()).name("qin").age(26));
        assertTrue(updated);
        assertEquals(26, repo.findById(user.id()).age());
    }

    @Test
    void findAllAndDelete() {
        repo.save(repo.createEntity().name("a").age(1));
        repo.save(repo.createEntity().name("b").age(2));
        repo.save(repo.createEntity().name("c").age(3));

        assertEquals(3, repo.findAll().size());
        assertTrue(repo.existsById(1L));
        assertEquals(3, repo.count());

        assertTrue(repo.deleteById(1L));
        assertFalse(repo.existsById(1L));
        assertEquals(2, repo.count());
    }

    @Test
    void batchOperations() {
        repo.save(List.of(
                repo.createEntity().name("a").age(1),
                repo.createEntity().name("b").age(2),
                repo.createEntity().name("c").age(3)));

        assertEquals(3, repo.findByIds(List.of(1L, 2L, 3L)).size());
        assertEquals(2, repo.deleteByIds(List.of(1L, 3L)));
        assertEquals(1, repo.count());

        List<UserEntity> remaining = repo.findAll();
        assertTrue(repo.delete(remaining.get(0)));
        assertEquals(0, repo.count());
    }

    @Test
    void createEntityReturnsEmptyInstance() {
        UserEntity user = repo.createEntity();

        assertNotNull(user);
        assertNull(user.id());
        assertNull(user.name());
        assertNull(user.age());
    }

    @Test
    void queryFullEntityByCondition() {
        repo.save(List.of(
                repo.createEntity().name("a").age(10),
                repo.createEntity().name("b").age(20),
                repo.createEntity().name("c").age(30)));

        List<UserEntity> adults = repo.findByAgeGreaterThan(15);

        assertEquals(2, adults.size());
        assertTrue(adults.stream().allMatch(u -> u.age() > 15));
    }

    @Test
    void queryDtoProjection() {
        repo.save(repo.createEntity().name("qin").age(25));

        UserNameDto dto = repo.findNameById(1L);

        assertNotNull(dto);
        assertEquals(1L, dto.id());
        assertEquals("qin", dto.userName());
    }

    @Test
    void queryScalarAndUpdate() {
        repo.save(List.of(
                repo.createEntity().name("a").age(10),
                repo.createEntity().name("b").age(20)));

        assertEquals(1, repo.countByAge(10));

        int affected = repo.updateAge(2L, 99);
        assertEquals(1, affected);
        assertEquals(99, repo.findById(2L).age());
    }
}
