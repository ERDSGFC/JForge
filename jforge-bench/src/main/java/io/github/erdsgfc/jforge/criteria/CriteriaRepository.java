package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Delete;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.UpdateSet;
import io.github.erdsgfc.jforge.annotation.Update;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.core.BaseRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** 条件对象查询（@Where / Optional IS NULL）与声明式更新（@Update/@Set）的验证仓库。 */
@Dao
public interface CriteriaRepository extends BaseRepository<CriteriaUser, Long> {

    /** 条件对象：值字段 / @Or / Optional IS NULL / 嵌套括号分组。 */
    @Select
    List<CriteriaUser> findComplex(@Where UserCriteria user);

    /** value=false 允许条件对象未标注 @JForgeSql。 */
    @Select
    List<CriteriaUser> findByLoose(@Where(value = false) LooseCriteria criteria);

    /** Optional 参数：isEmpty → user_name IS NULL；有值 → user_name = ?（列映射 name 字段）。 */
    @Select
    List<CriteriaUser> findByNickname(@Condition(value = "name") @Nullable Optional<String> nickname);

    /** 声明式更新：SET 列 + WHERE 条件（全静态 → SQL 常量）。 */
    @Update
    int updateName(@UpdateSet String name, @Condition Long id);

    /** 声明式更新：@Nullable SET 参数为 null 时跳过该 SET。 */
    @Update
    int updateNameAndAge(@UpdateSet String name, @UpdateSet @Nullable Integer age, @Condition Long id);

    /** 集合 {@code NOT IN} WHERE 条件用于声明式更新。 */
    @Update
    int updateNameExcluding(@UpdateSet String name,
            @Condition(value = "id", op = Op.NE) List<Long> excludedIds);

    /** 声明式更新：Optional SET 空 → SET 列 = NULL。 */
    @Update
    int updateNickname(@UpdateSet(value = "name") @Nullable Optional<String> nickname, @Condition Long id);

    /** 声明式更新：WHERE 用条件对象（@Where）。 */
    @Update
    int updateByCriteria(@UpdateSet String name, @Where UserCriteria criteria);

    /** 声明式删除：WHERE 条件（全静态 → SQL 常量）。 */
    @Delete
    int deleteByIdCondition(@Condition Long id);

    /** 集合 {@code NOT IN} WHERE 条件用于声明式删除。 */
    @Delete
    int deleteByIdNotIn(@Condition(value = "id", op = Op.NE) List<Long> excludedIds);

    /** 声明式删除：Optional WHERE 参数（isEmpty → IS NULL）。 */
    @Delete
    int deleteByNickname(@Condition(value = "name") @Nullable Optional<String> nickname);

    /** 声明式删除：WHERE 用条件对象（@Where 嵌套分组）。 */
    @Delete
    int deleteByCriteria(@Where UserCriteria criteria);

    /** rawSql 常量条件：WHERE age > 20（参数不绑定，仅用于跳过控制）。 */
    @Select
    List<CriteriaUser> findOlderThanRaw(@Condition(rawSql = "age > 20") Integer ignored);

    /** rawSql SET 表达式：score = score + ?（绑定参数）。 */
    @Update
    int incrementScore(@UpdateSet(rawSql = "score = score + ?") Integer increment, @Condition Long id);

    /** 条件对象作 @Update 载体：@UpdateSet 字段 → SET，其余字段 → WHERE。 */
    @Update
    int updateByCriteria(@Where UserUpdateCriteria update);

    /** rawSql 删除条件：WHERE age > 25。 */
    @Delete
    int deleteOldRaw(@Condition(rawSql = "age > 25") Integer ignored);
}
