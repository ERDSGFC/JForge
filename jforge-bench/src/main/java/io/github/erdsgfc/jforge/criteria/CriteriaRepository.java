package io.github.erdsgfc.jforge.criteria;

import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;
import java.util.Optional;

/** 条件对象查询（@Where / Optional IS NULL）的验证仓库。 */
@Dao
public interface CriteriaRepository extends BaseRepository<CriteriaUser, Long> {

    /** 条件对象：值字段 / @Or / Optional IS NULL / 嵌套括号分组。 */
    @Select
    List<CriteriaUser> findComplex(@Where UserCriteria criteria);

    /** Optional 参数：isEmpty → user_name IS NULL；有值 → user_name = ?（列映射 name 字段）。 */
    @Select
    List<CriteriaUser> findByNickname(@Condition(value = "name") Optional<String> nickname);
}
