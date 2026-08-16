package io.github.erdsgfc.jforge.logsql;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 测试仓库;其生成实现经 {@code @JForgeConfig(logSql=true)} 输出 SQL 日志。 */
@Dao
public interface LogUserRepository extends BaseRepository<LogUser, Long> {
}
