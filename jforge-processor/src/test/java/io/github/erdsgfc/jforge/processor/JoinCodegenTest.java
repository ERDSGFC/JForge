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
        assertTrue(generated.contains("LEFT JOIN \\\"departments\\\" ON \\\"users\\\".departmentId = \\\"departments\\\".\\\"id\\\""),
                generated);
        assertTrue(generated.contains("\\\"departments\\\".\\\"name\\\" = ?"), generated);
    }
}
