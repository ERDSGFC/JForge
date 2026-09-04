package io.github.erdsgfc.jforge;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.core.BaseRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * 测试仓库:继承 {@link BaseRepository} 的 CRUD,并添加
 * 标注 {@link Query} 的自定义方法,实现由编译期生成。
 * {@link Select} 方法不用写 SQL——处理器按返回类型与参数自动拼接。
 */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {

    /** 按列名映射的完整实体查询。 */
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind int age);

    /** 部分字段投影到 DTO record。 */
    @Query("SELECT id, user_name FROM users WHERE id = :id")
    UserNameDto findNameById(@Bind long id);

    /** 标量计数。 */
    @Query("SELECT COUNT(*) FROM users WHERE age = :age")
    long countByAge(@Bind int age);

    /** 返回受影响行数的更新语句。 */
    @Query("UPDATE users SET age = :age WHERE id = :id")
    int updateAge(@Bind long id, @Bind int age);

    @Query("SELECT id, user_name, age FROM users")
    List<UserEntity> findByAutoQueryParameter(String name);

    /** 动态 WHERE：age 为 null 时按 JSpecify 空性规则跳过。 */
    @Query("SELECT id, user_name, age FROM users WHERE age = :age AND user_name = :name")
    List<UserEntity> findDynamicByAgeAndName(@Bind @Nullable Integer age,
            @Bind String name);

    /** 动态 WHERE：@Nullable 自动推断（片段仅一个占位符）。 */
    @Query("SELECT id, user_name, age FROM users WHERE user_name = :name AND age = :age")
    List<UserEntity> findAutoDynamicByAgeAndName(@Bind String name,
            @Bind @Nullable Integer age);

    /** 动态 WHERE：OR 连接符保留。 */
    @Query("SELECT id, user_name, age FROM users WHERE age = :age OR user_name = :name")
    List<UserEntity> findDynamicOr(@Bind @Nullable Integer age, @Bind String name);

    /** @Condition 追加条件：手写 SQL + 自动追加（age 非 null 时 AND age > ?，动态）。 */
    @Query("SELECT id, user_name, age FROM users WHERE user_name = :name AND {:age}")
    List<UserEntity> findWithAppendedWhere(@Bind String name,
            @Condition(op = Op.GT) @Nullable Integer age);

    /** @Condition 追加条件：静态追加（恒拼接），无 @Nullable。 */
    @Query("SELECT id, user_name, age FROM users WHERE user_name = :name AND {:minAge}")
    List<UserEntity> findWithAppendedStaticWhere(@Bind String name,
            @Condition(value = "age", op = Op.GE) int minAge);

    // ---- @Select 声明式查询（不写 SQL，参数即条件，默认等于）----

    /** 静态条件：{@code WHERE user_name = ?}。 */
    @Select
    List<UserEntity> findByName(String name);

    /** 动态条件：{@code age} 为 {@code null} 时查全表。 */
    @Select
    List<UserEntity> findByAge(@Nullable Integer age);

    /** 静态 + 动态混合。 */
    @Select
    List<UserEntity> findByAgeAndName(@Nullable Integer age, String name);

    /** Iterable/数组参数统一生成 IN 条件。 */
    @Select
    List<UserEntity> findByIdIn(List<Long> id);

    @Select
    List<UserEntity> findByIdArray(long[] id);

    /** {@code @Condition(op = Op.NE)} 集合参数生成 {@code NOT IN}。 */
    @Select
    List<UserEntity> findByIdNotIn(@Condition(value = "id", op = Op.NE) List<Long> ids);

    @Select
    List<UserEntity> findByIdNotInArray(@Condition(value = "id", op = Op.NE) long[] ids);

    /** {@code @Query + @Condition(op = Op.NE)} 集合参数动态展开。 */
    @Query("SELECT id, user_name, age FROM users WHERE {:ids}")
    List<UserEntity> findByQueryNotIn(@Condition(value = "id", op = Op.NE) List<Long> ids);

    @Query("SELECT id, user_name, age FROM users WHERE {:ids}")
    List<UserEntity> findByQueryNotInArray(@Condition(value = "id", op = Op.NE) long[] ids);

    /** 操作符：{@code age > ?}。 */
    @Select
    List<UserEntity> findOlderThan(@Condition(op = Op.GT) Integer age);

    /** LIKE 操作符。 */
    @Select
    List<UserEntity> findByNameLike(@Condition(op = Op.LIKE) String name);

    /** 标量：{@code SELECT COUNT(*) WHERE user_name = ?}。 */
    @Select
    long countByName(String name);

    /** record 投影（组件名经命名策略：{@code userName} → {@code user_name}）。 */
    @Select
    List<UserNameDto> findNameDtoById(Long id);

    /** 无参数：查全表。 */
    @Select
    List<UserEntity> findAllUsers();

    // ---- 动态判定矩阵（基本类型恒固定 / 引用类型按 JSpecify 作用域 / Optional 独立 IS NULL）----

    /** 基本类型参数：恒固定（静态 SQL 常量形态）。 */
    @Select
    List<UserEntity> findAdults(int age);

    /** 未标注 boxed 参数位于非 @NullMarked 作用域：默认可空，null 时动态跳过条件。 */
    @Select
    List<UserEntity> findByBoxedAge(Integer age);

    /** Optional + @Nullable：null 守卫（null → 跳过整个条件）+ IS NULL 语义（空 → IS NULL）。 */
    @Select
    List<UserEntity> findByNicknameNullable(@Condition(value = "name") @Nullable Optional<String> nickname);

    /** 双动态条件组合：两个都为 null → 无 WHERE 查全表。 */
    @Select
    List<UserEntity> findByAgeRange(@Condition(value = "age", op = Op.GT) @Nullable Integer minAge,
            @Condition(value = "age", op = Op.LT) @Nullable Integer maxAge);

    /** 全参数动态：全部 null → 无 WHERE 查全表（where 前缀变量兜底）。 */
    @Select
    List<UserEntity> findByAgeOrName(@Nullable Integer age, @Nullable String name);
}
