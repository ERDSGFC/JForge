package io.github.erdsgfc.jforge.benchmark;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/** 手动主键实体的基准仓库（save 无生成键回写路径）。 */
@Dao
public interface ManualIdUserRepository extends BaseRepository<ManualIdUser, Long> {
}
