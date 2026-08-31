package io.github.erdsgfc.jforge.pgsql.readonly;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 只读主键实体的仓库（save 后 id 仍回写——RETURNING 强转路径的真 PG 验证）。 */
@Dao
public interface AutoIdPgUserRepository extends BaseRepository<AutoIdPgUser, Long> {
}
