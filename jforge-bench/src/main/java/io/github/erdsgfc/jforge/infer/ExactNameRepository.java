package io.github.erdsgfc.jforge.infer;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** {@code tableNaming = NONE} 实体（表名 = 实体名原样）的仓库。 */
@Dao
public interface ExactNameRepository extends BaseRepository<ExactNameEntity, Long> {
}
