package io.github.erdsgfc.jforge;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.core.BaseRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 测试仓库:继承 {@link BaseRepository} 的 CRUD,并添加
 * 标注 {@link Query} 的自定义方法,实现由编译期生成。
 * {@link Select} 方法不用写 SQL——处理器按返回类型与参数自动拼接。
 */
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {

    /** 按列名映射的完整实体查询。 */
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind("age") int age);

    /** 部分字段投影到 DTO record。 */
    @Query("SELECT id, user_name FROM users WHERE id = :id")
    UserNameDto findNameById(@Bind("id") long id);

    /** 标量计数。 */
    @Query("SELECT COUNT(*) FROM users WHERE age = :age")
    long countByAge(@Bind("age") int age);

    /** 返回受影响行数的更新语句。 */
    @Query("UPDATE users SET age = :age WHERE id = :id")
    int updateAge(@Bind("id") long id, @Bind("age") int age);

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

    /** 操作符：{@code age > ?}。 */
    @Select
    List<UserEntity> findOlderThan(@Where(op = Op.GT) Integer age);

    /** LIKE 操作符。 */
    @Select
    List<UserEntity> findByNameLike(@Where(op = Op.LIKE) String name);

    /** 标量：{@code SELECT COUNT(*) WHERE user_name = ?}。 */
    @Select
    long countByName(String name);

    /** record 投影（组件名经命名策略：{@code userName} → {@code user_name}）。 */
    @Select
    List<UserNameDto> findNameDtoById(Long id);

    /** 无参数：查全表。 */
    @Select
    List<UserEntity> findAllUsers();
}
