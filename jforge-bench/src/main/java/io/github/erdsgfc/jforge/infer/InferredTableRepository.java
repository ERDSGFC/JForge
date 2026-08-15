package io.github.erdsgfc.jforge.infer;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 无 {@code @Table} 实体的仓库,验证表名推断链路(DDL/CRUD 全部用推断表名)。
 */
@Dao
public interface InferredTableRepository extends BaseRepository<InferredTableEntity, Long> {
}
