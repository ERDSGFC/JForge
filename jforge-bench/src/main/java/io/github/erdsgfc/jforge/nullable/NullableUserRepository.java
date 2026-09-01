package io.github.erdsgfc.jforge.nullable;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;

/** 列空性验证实体的仓库。 */
@Dao
public interface NullableUserRepository extends BaseRepository<NullableUser, Long> {

    /**
     * 参数动态判定验证：{@code score} 未标 {@code @Nullable}、非基本类型——
     * 包配置 {@code columnsNullable = true} 时默认动态（null → 跳过条件查全表）。
     */
    @Select
    List<NullableUser> findByScore(Integer score);
}
