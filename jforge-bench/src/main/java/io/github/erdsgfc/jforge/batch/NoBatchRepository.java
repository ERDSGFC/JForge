package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.BatchSize;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * 用 {@code @BatchSize(0)} 显式关闭批处理的批处理测试仓库:
 * 行数据在单个连接上逐条插入。
 */
@Dao
@BatchSize(0)
public interface NoBatchRepository extends BaseRepository<BatchUser, Long> {
}
