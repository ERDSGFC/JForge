package io.github.erdsgfc.jforge.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成代码的 null 快速失败验证（{@code Nullability.requireNonNull}）：
 * {@code @NonNull} 契约参数在方法/IN 分支开头生成
 * {@code Objects.requireNonNull(param, "<operation>: <name> must not be null")}；
 * {@code @Nullable} 参数（动态条件，null 合法跳过）不生成。
 */
class NullCheckCodegenTest {

    @Test
    void crudMethodsFailsFastOnNonNullParams() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);
                    String name(); User name(String v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.UserRepository_Impl");

        // CRUD 入口:@NonNull 契约参数快速失败,消息带操作名区分同名参数。
        assertTrue(generated.contains("Objects.requireNonNull(id, \"findById: id must not be null\")"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(id, \"deleteById: id must not be null\")"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(id, \"existsById: id must not be null\")"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(entity, \"save: entity must not be null\")"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(entity, \"update: entity must not be null\")"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(ids, \"findByIds: ids must not be null\")"), generated);
        // 空集合语义保留:null 快速失败后仍校验 isEmpty。
        assertTrue(generated.contains("if (ids.isEmpty())"), generated);
    }

    /** {@code @NonNull} 契约的集合/数组条件参数:@Update 的 IN 分支同样快速失败。 */
    @Test
    void updateNonNullCollectionConditionFailsFast() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import org.jspecify.annotations.NonNull;
                import java.util.List;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);
                    int status(); User status(int v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                    @Update int updateStatus(@UpdateSet int status,
                            @Condition(value = "id") @NonNull List<Long> ids);
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.UserRepository_Impl");
        // @NonNull 集合(dynamic=false,无 null 守卫):IN 分支开头快速失败。
        assertTrue(generated.contains("Objects.requireNonNull(ids, \"ids must not be null\")"), generated);
        // 空集合语义保留(1 = 0)。
        assertTrue(generated.contains("1 = 0"), generated);
    }

    /** {@code @Nullable} 动态参数:null 合法跳过(guard),不生成 requireNonNull。 */
    @Test
    void nullableDynamicParamKeepsGuardWithoutRequireNonNull() throws Exception {
        String source = """
                package test;
                import io.github.erdsgfc.jforge.annotation.*;
                import io.github.erdsgfc.jforge.core.BaseRepository;
                import org.jspecify.annotations.Nullable;
                import java.util.List;
                @Table(name = "users") interface User {
                    @Id Long id(); User id(Long v);
                    String name(); User name(String v);
                }
                @Dao public interface UserRepository extends BaseRepository<User, Long> {
                    @Select List<User> findByName(@Nullable String name);
                }
                """;
        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new JForgeProcessor());
        assertTrue(result.success, () -> result.diagnostics.toString());
        String generated = result.generatedSources.get("test.UserRepository_Impl");
        // 动态条件走 null 守卫(跳过),不生成 requireNonNull——null 是合法查询语义。
        assertTrue(generated.contains("if (name != null)"), generated);
        assertFalse(generated.contains("requireNonNull(name"), generated);
    }
}
