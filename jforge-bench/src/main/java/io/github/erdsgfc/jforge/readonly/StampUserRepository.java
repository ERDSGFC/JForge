package io.github.erdsgfc.jforge.readonly;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 非主键只读列（数据库默认值维护）实体的仓库。 */
@Dao
public interface StampUserRepository extends BaseRepository<StampUser, Long> {
}
