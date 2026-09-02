package io.github.erdsgfc.jforge.nullable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.erdsgfc.jforge.core.JForge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 列空性（wasNull 映射）的集成测试：可空列 NULL 读回 null，基本类型读 0。 */
class NullableColumnTest {

    private HikariDataSource ds;
    private NullableUserRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:orm_nullable;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        ds = new HikariDataSource(config);
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS nullable_users");
            st.execute("CREATE TABLE nullable_users (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "name VARCHAR(100)," +
                    "nickname VARCHAR(100)," +
                    "age INT," +
                    "score INT)");
        }
        repo = new JForge(ds).repository(NullableUserRepository.class);
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    /** 全 NULL 行：显式可空列读回 null，基本类型读 0。 */
    @Test
    void nullRowMapsNullableColumnsToNull() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO nullable_users (name, nickname, age, score) VALUES (NULL, 'required', NULL, NULL)");
        }

        NullableUser user = repo.findById(1L);

        assertNull(user.name(), "@Nullable annotated String column should read back null");
        assertEquals("required", user.nickname(), "@NonNull String column should read back normally");
        assertEquals(0, user.age(), "primitive int column has no wasNull branch — NULL reads 0");
        assertNull(user.score(), "explicitly nullable boxed Integer column should read back null");
    }

    /** @Nullable boxed 参数为 null 时动态跳过条件。 */
    @Test
    void nullableBoxedParameterIsDynamic() {
        repo.save(repo.createEntity().name("a").nickname("n1").age(10).score(90));
        repo.save(repo.createEntity().name("b").nickname("n2").age(20).score(80));

        assertEquals(1, repo.findByScore(90).size(), "non-null value must filter");
        assertEquals(2, repo.findByScore(null).size(), "@Nullable null parameter must skip the condition");
    }

    @Test
    void nonNullRowReadsValuesNormally() {
        NullableUser user = repo.createEntity();
        user.name("qin");
        user.nickname("qq");
        user.age(25);
        user.score(88);
        repo.save(user);

        NullableUser found = repo.findById(user.id());

        assertEquals("qin", found.name());
        assertEquals("qq", found.nickname());
        assertEquals(25, found.age());
        assertEquals(88, found.score());
    }

    /** 生成实现应明确输出列空性，以及 @NullMarked 方法的默认非空返回类型。 */
    @Test
    void generatedTypesCarryJSpecifyAnnotations() throws ReflectiveOperationException {
        Class<?> entityImpl = Class.forName(
                "io.github.erdsgfc.jforge.nullable.NullableUserRepository_Impl$NullableUser_Impl");

        assertNotNull(entityImpl.getDeclaredField("name").getAnnotatedType().getAnnotation(Nullable.class));
        assertNotNull(entityImpl.getDeclaredField("nickname").getAnnotatedType().getAnnotation(NonNull.class));
        assertNotNull(entityImpl.getDeclaredMethod("name").getAnnotatedReturnType()
                .getAnnotation(Nullable.class));
        assertNotNull(entityImpl.getDeclaredMethod("nickname").getAnnotatedReturnType()
                .getAnnotation(NonNull.class));
        assertNotNull(entityImpl.getDeclaredMethod("name", String.class).getAnnotatedParameterTypes()[0]
                .getAnnotation(Nullable.class));
        assertNotNull(entityImpl.getDeclaredMethod("name", String.class).getAnnotatedReturnType()
                .getAnnotation(NonNull.class));

        Method findByScore = NullableUserRepository_Impl.class.getDeclaredMethod("findByScore", Integer.class);
        assertNotNull(findByScore.getAnnotatedReturnType().getAnnotation(NonNull.class));
        assertNotNull(findByScore.getAnnotatedParameterTypes()[0].getAnnotation(Nullable.class));
    }
}
