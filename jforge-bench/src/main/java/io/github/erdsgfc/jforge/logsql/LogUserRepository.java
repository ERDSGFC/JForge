package io.github.erdsgfc.jforge.logsql;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** Test repository; its generated impl emits SQL logging via {@code @JForgeConfig(logSql=true)}. */
@Dao
public interface LogUserRepository extends BaseRepository<LogUser, Long> {
}
