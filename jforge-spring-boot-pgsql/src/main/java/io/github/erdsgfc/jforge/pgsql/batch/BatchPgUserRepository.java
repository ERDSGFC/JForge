package io.github.erdsgfc.jforge.pgsql.batch;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 批处理仓库（batchSize=2，包级配置）。 */
@Dao
public interface BatchPgUserRepository extends BaseRepository<BatchPgUser, Long> {
}
