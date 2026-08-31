package io.github.erdsgfc.jforge.pgsql;

import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Delete;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.Update;
import io.github.erdsgfc.jforge.annotation.UpdateSet;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.core.BaseRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 真 PostgreSQL 集成测试仓库：继承 {@link BaseRepository} 的 CRUD（save 走
 * {@code INSERT ... RETURNING} 生成键回写），并添加声明式查询方法——验证引用符
 * 包裹后的生成 SQL 在真 PG 上的全链路（含保留字列 {@code "order"}）。
 */
@Dao
public interface PgUserRepository extends BaseRepository<PgUser, Long> {

    // ---- @Select 声明式查询（不写 SQL，参数即条件）----

    /** 静态条件：{@code WHERE "user_name" = ?}。 */
    @Select
    List<PgUser> findByUserName(String userName);

    /** 动态条件：{@code age} 为 {@code null} 时查全表。 */
    @Select
    List<PgUser> findByAge(@Nullable Integer age);

    /** 保留字列条件：{@code WHERE "order" > ?}（引用符包裹在真库上的验证）。 */
    @Select
    List<PgUser> findByOrderGreaterThan(@Condition(op = Op.GT) Integer order);

    /** record 投影：{@code SELECT "id","user_name" FROM ...}（组件名经命名策略）。 */
    @Select
    List<PgUserNameDto> findNameDtoById(Long id);

    /** 标量：{@code SELECT COUNT(*) WHERE "user_name" = ?}。 */
    @Select
    long countByUserName(String userName);

    /** 条件对象：值条件 / Optional IS NULL / 括号分组 / rawSql 常量片段。 */
    @Select
    List<PgUser> findByCriteria(@Where PgUserCriteria criteria);

    /** Optional 参数：空 → IS NULL，有值 → 等于条件。 */
    @Select
    List<PgUser> findByNickname(@Condition(value = "userName") java.util.Optional<String> nickname);

    // ---- @Update / @Delete 声明式写操作 ----

    /** 动态 SET：{@code age} 为 {@code null} 时跳过该 SET 列。 */
    @Update
    int updateAgeById(@UpdateSet @Nullable Integer age, @Condition Long id);

    /** 静态 SET + Optional SET（空 → SET NULL）。 */
    @Update
    int updateNameAndOrder(@UpdateSet String userName, @UpdateSet @Nullable Integer order,
            @Condition Long id);

    /** Optional SET：空 → SET {@code "user_name"} = NULL。 */
    @Update
    int updateNickname(@UpdateSet(value = "userName") java.util.Optional<String> nickname,
            @Condition Long id);

    /** 条件对象删除。 */
    @Delete
    int deleteByCriteria(@Where PgUserCriteria criteria);

    /** 普通参数条件删除：{@code WHERE "user_name" = ?}。 */
    @Delete
    int deleteByUserName(String userName);

    // ---- @Query 手写 SQL（用户 SQL 原样透传，保留字需自行引号）----

    /** 分页：PG 方言 {@code LIMIT ? OFFSET ?} 语法验证。 */
    @Query("SELECT * FROM pg_users WHERE age > :age ORDER BY id LIMIT :limit OFFSET :offset")
    List<PgUser> pageByAge(@Bind("age") int age, @Bind("limit") int limit, @Bind("offset") int offset);

    /** 动态 WHERE：方括号显式动态段（age 为 null 时跳过）。 */
    @Query("SELECT * FROM pg_users WHERE [age = :age] AND user_name = :name")
    List<PgUser> findDynamicByAgeAndName(@Bind("age") @Nullable Integer age,
            @Bind("name") String name);
}
