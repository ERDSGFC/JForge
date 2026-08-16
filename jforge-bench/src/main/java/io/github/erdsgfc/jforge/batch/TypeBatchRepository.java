package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.BatchSize;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 在仓库类型上用 {@code @BatchSize(3)} 覆盖包级批处理大小的批处理测试仓库。
 */
@Dao
@BatchSize(3)
public interface TypeBatchRepository extends BaseRepository<BatchUser, Long> {
}