package io.github.erdsgfc.jforge.readonly;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 部分只读实体（只读 id + 可写字段）的仓库。 */
@Dao
public interface AutoIdUserRepository extends BaseRepository<AutoIdUser, Long> {
}
