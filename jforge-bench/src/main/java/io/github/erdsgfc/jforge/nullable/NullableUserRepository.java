package io.github.erdsgfc.jforge.nullable;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 列空性验证实体的仓库。 */
@Dao
public interface NullableUserRepository extends BaseRepository<NullableUser, Long> {
}
