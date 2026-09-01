package io.github.erdsgfc.jforge.convert;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @Convert} 自定义类型转换：save/批量 save/update/行映射全路径——数据库列存
 * 转换后文本（{@code "yyyy-MM-dd"}），实体读回 {@code LocalDate}。
 */
class ConvertTest {

    private HikariDataSource ds;
    private ConvertUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_convert;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS convert_users");
            st.execute("CREATE TABLE convert_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "name VARCHAR(100)," +
                    "birth_date VARCHAR(20)," +
                    "hire_date VARCHAR(20))");
        }
        repo = new JForge(ds).repository(ConvertUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** save 经转换器绑定（DB 存文本），行映射经转换器读回 LocalDate。 */
    @Test
    void saveAndReadBackThroughConverter() throws SQLException {
        LocalDate birth = LocalDate.of(2000, 1, 2);
        LocalDate hire = LocalDate.of(2024, 6, 15);

        ConvertUser saved = repo.save(repo.createEntity().name("qin").birthDate(birth).hireDate(hire));

        assertNotNull(saved.id());
        ConvertUser found = repo.findById(saved.id());
        assertEquals(birth, found.birthDate());
        assertEquals(hire, found.hireDate());

        // 数据库实际存储的是转换后的文本。
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT birth_date, hire_date FROM convert_users WHERE id = " + saved.id());
            rs.next();
            assertEquals("2000-01-02", rs.getString(1));
            assertEquals("2024-06-15", rs.getString(2));
        }
    }

    /** 批量 save：转换器列在批量绑定路径同样生效。 */
    @Test
    void saveAllThroughConverter() {
        List<ConvertUser> users = new ArrayList<>();
        users.add(repo.createEntity().name("a").birthDate(LocalDate.of(2000, 1, 2)));
        users.add(repo.createEntity().name("b").birthDate(LocalDate.of(2010, 3, 4)));

        repo.save(users);

        assertEquals(2, repo.count());
        assertEquals(LocalDate.of(2000, 1, 2), repo.findById(users.get(0).id()).birthDate());
        assertEquals(LocalDate.of(2010, 3, 4), repo.findById(users.get(1).id()).birthDate());
    }

    /** update：SET 列经转换器绑定。 */
    @Test
    void updateThroughConverter() {
        ConvertUser saved = repo.save(repo.createEntity().name("qin").birthDate(LocalDate.of(2000, 1, 2)));
        LocalDate newBirth = LocalDate.of(1995, 12, 31);

        saved.birthDate(newBirth);
        assertNotNull(repo.update(saved));

        assertEquals(newBirth, repo.findById(saved.id()).birthDate());
    }

    /** null 透传：转换器列留 null 时绑定 NULL、读回 null。 */
    @Test
    void nullPassesThroughConverter() {
        ConvertUser saved = repo.save(repo.createEntity().name("null"));

        assertNull(repo.findById(saved.id()).birthDate());
        assertNull(repo.findById(saved.id()).hireDate());
    }

    /** {@code @Query} 按转换列查询：{@code @Bind} 挂同一转换器，参数生成相同存储表示才命中。 */
    @Test
    void queryByConvertedColumnThroughBindConverter() {
        LocalDate birth = LocalDate.of(2000, 1, 2);
        ConvertUser saved = repo.save(repo.createEntity().name("qin").birthDate(birth));

        ConvertUser found = repo.findByBirthDate(birth);
        assertNotNull(found);
        assertEquals(saved.id(), found.id());
        assertNull(repo.findByBirthDate(birth.plusDays(1)));
    }

    /** 动态路径：{@code @Nullable} 参数 + {@code @Bind} 转换器（显式 {@code sqlType()=VARCHAR}）。 */
    @Test
    void queryByConvertedColumnDynamicPath() {
        LocalDate hire = LocalDate.of(2024, 6, 15);
        repo.save(repo.createEntity().name("a").birthDate(LocalDate.of(2000, 1, 2)).hireDate(hire));
        repo.save(repo.createEntity().name("b").birthDate(LocalDate.of(2010, 3, 4))
                .hireDate(LocalDate.of(2025, 1, 1)));

        // 非 null：条件经转换器绑定（VARCHAR 显式类型）命中对应行。
        assertEquals("a", repo.findNullableByHireDate(hire).name());
        // null：动态条件跳过（无 WHERE 取首行）——验证动态路径无回归。
        assertEquals("a", repo.findNullableByHireDate(null).name());
    }
}
