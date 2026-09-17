package io.github.erdsgfc.jforge.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link io.github.erdsgfc.jforge.annotation.Join} 的编译期 SQL 生成验证。 */
class JoinCodegenTest {

    @Test
    void generatesJoinAndQualifiesJoinedCondition() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import java.util.List;
                @Table(name = \"users\") interface User {
                    @Id Long id(); User id(Long v);
                    Long departmentId(); User departmentId(Long v);
                }
                @Table(name = \"departments\") interface Department {
                    @Id Long id(); Department id(Long v);
                    String name(); Department name(String v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                    @Select
                    @Join(entity = Department.class, type = JoinType.LEFT,
                          on = @Join.On(local = \"departmentId\", target = \"id\"))
                    List<User> findByDepartment(@Condition(value = \"name\", entity = Department.class) String name);
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.UserRepository_Impl");
        assertTrue(generated != null, "repository implementation was not generated");
        assertTrue(generated.contains("LEFT JOIN \\\"departments\\\" ON \\\"users\\\".\\\"departmentId\\\" = \\\"departments\\\".\\\"id\\\""),
                generated);
        assertTrue(generated.contains("\\\"departments\\\".\\\"name\\\" = ?"), generated);
    }

    @Test
    void acceptsNotInForCollectionAndRejectsOtherCollectionOperators() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import java.util.List;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);
                }
                @Dao public interface GoodRepository extends BaseRepository<User, Long> {
                    @Select List<User> find(@Condition(value = "id", op = Op.NE) List<Long> ids);
                }
                """;
        CompilationHelper.CompilationResult good = CompilationHelper.compile(
                "test.GoodRepository", source, new JForgeProcessor());
        assertTrue(good.success, () -> good.diagnostics.toString());
        assertTrue(good.generatedSources.get("test.GoodRepository_Impl").contains("NOT IN"));

        String invalid = source.replace("GoodRepository", "BadRepository")
                .replace("op = Op.NE", "op = Op.GT");
        CompilationHelper.CompilationResult bad = CompilationHelper.compile(
                "test.BadRepository", invalid, new JForgeProcessor());
        assertTrue(!bad.success, "non-EQ/NE collection operators must be rejected");
        assertTrue(bad.diagnostics.stream().anyMatch(d -> d.getMessage(null).contains("only support")),
                () -> bad.diagnostics.toString());
    }

    @Test
    void queryConditionValueUsesNativeAliasedColumn() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import java.util.List;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                    @Query("SELECT u.id FROM users u WHERE {:name}")
                    List<User> find(@Condition(value = "u.display_name") String name);
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.UserRepository_Impl");
        assertTrue(generated != null, "repository implementation was not generated");
        assertTrue(generated.contains("u.display_name = ?"), generated);
    }
    @Test
    void acceptsPrimitiveEntityIdWithWrapperRepositoryId() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import java.util.List;
                @Table(name = "users") interface User {
                    @Id long id(); User id(long v);
                    String name(); User name(String v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.UserRepository_Impl");
        assertTrue(generated != null, "repository implementation was not generated");
        assertTrue(generated.contains("Long id)"), generated);
        assertTrue(generated.contains("deleteById(@Nullable Long id)") || generated.contains("deleteById(@NonNull Long id)") || generated.contains("deleteById(Long id)"), generated);
        assertTrue(generated.contains("List<Long> ids)"), generated);
        assertTrue(generated.contains("requireNonNull(id"), generated);
    }

    @Test
    void acceptsIntEntityIdWithIntegerRepositoryId() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import java.util.List;
                @Table(name = "items") interface Item {
                    @Id int id(); Item id(int v);
                }
                @Dao public interface ItemRepository extends BaseRepository<Item, Integer> {
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.ItemRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.ItemRepository_Impl");
        assertTrue(generated != null, "repository implementation was not generated");
        assertTrue(generated.contains("Integer id)"), generated);
        assertTrue(generated.contains("List<Integer> ids)"), generated);
        assertTrue(generated.contains("requireNonNull(id"), generated);
    }

    /**
     * {@code @Join.from} 缺省时从 ON 左侧字段推断：{@code local = "companyId"} 只存在于
     * Department，故第二个 {@code @Join} 无需书写 {@code from = Department.class}，
     * 生成的 SQL 与显式书写时一致。
     */
    @Test
    void infersFromFromOnLocalField() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import java.util.List;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);
                    Long departmentId(); User departmentId(Long v);
                }
                @Table(name = "departments") interface Department {
                    @Id Long id(); Department id(Long v);
                    Long companyId(); Department companyId(Long v);
                }
                @Table(name = "companies") interface Company {
                    @Id Long id(); Company id(Long v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                    @Select
                    @Join(entity = Department.class, on = @Join.On(local = "departmentId", target = "id"))
                    @Join(entity = Company.class,    on = @Join.On(local = "companyId",    target = "id"))
                    List<User> findByCompany(@Condition(value = "id", entity = Company.class) Long id);
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.UserRepository_Impl");
        assertTrue(generated != null, "repository implementation was not generated");
        // 推断出的 from = Department：ON 左侧限定到 departments 表。
        assertTrue(generated.contains(
                "INNER JOIN \\\"companies\\\" ON \\\"departments\\\".\\\"companyId\\\" = \\\"companies\\\".\\\"id\\\""),
                generated);
    }

    /**
     * 推断歧义（{@code local} 字段在多个已连接实体中都存在）时报编译错误，
     * 要求显式指定 {@code from}——不猜连接方向。
     */
    @Test
    void rejectsAmbiguousInferredFrom() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import java.util.List;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);
                    Long departmentId(); User departmentId(Long v);
                }
                @Table(name = "departments") interface Department {
                    @Id Long id(); Department id(Long v);
                    Long companyId(); Department companyId(Long v);
                }
                @Table(name = "companies") interface Company {
                    @Id Long id(); Company id(Long v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                    @Select
                    @Join(entity = Department.class, on = @Join.On(local = "departmentId", target = "id"))
                    @Join(entity = Company.class,    on = @Join.On(local = "id",           target = "id"))
                    List<User> findByCompany(@Condition(value = "id", entity = Company.class) Long id);
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(!result.success, "ambiguous from must be rejected");
        assertTrue(result.diagnostics.stream()
                        .anyMatch(d -> d.getMessage(null).contains("@Join.from is ambiguous")),
                () -> result.diagnostics.toString());
    }
}
