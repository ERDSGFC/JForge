package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.UpdateSet;
import io.github.erdsgfc.jforge.annotation.Update;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.core.BaseRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** 条件对象查询（@Where / Optional IS NULL）与声明式更新（@Update/@Set）的验证仓库。 */
@Dao
public interface CriteriaRepository extends BaseRepository<CriteriaUser, Long> {

    /** 条件对象：值字段 / @Or / Optional IS NULL / 嵌套括号分组。 */
    @Select
    List<CriteriaUser> findComplex(@Where UserCriteria criteria);

    /** Optional 参数：isEmpty → user_name IS NULL；有值 → user_name = ?（列映射 name 字段）。 */
    @Select
    List<CriteriaUser> findByNickname(@Condition(value = "name") Optional<String> nickname);

    /** 声明式更新：SET 列 + WHERE 条件（全静态 → SQL 常量）。 */
    @Update
    int updateName(@UpdateSet String name, @Condition Long id);

    /** 声明式更新：@Nullable SET 参数为 null 时跳过该 SET。 */
    @Update
    int updateNameAndAge(@UpdateSet String name, @UpdateSet @Nullable Integer age, @Condition Long id);

    /** 声明式更新：Optional SET 空 → SET 列 = NULL。 */
    @Update
    int updateNickname(@UpdateSet(value = "name") Optional<String> nickname, @Condition Long id);

    /** 声明式更新：WHERE 用条件对象（@Where）。 */
    @Update
    int updateByCriteria(@UpdateSet String name, @Where UserCriteria criteria);
}
