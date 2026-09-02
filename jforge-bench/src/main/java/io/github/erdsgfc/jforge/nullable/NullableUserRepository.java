package io.github.erdsgfc.jforge.nullable;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.core.BaseRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** 列空性验证实体的仓库。 */
@Dao
public interface NullableUserRepository extends BaseRepository<NullableUser, Long> {

    /**
     * 参数动态判定验证：显式 {@code @Nullable} 的 score 为 null 时跳过条件查全表。
     */
    @Select
    List<NullableUser> findByScore(@Nullable Integer score);
}
