package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 带时间字段（框架自动维护）实体的基准仓库。 */
@Dao
public interface TimedUserRepository extends BaseRepository<TimedUserEntity, Long> {
}
