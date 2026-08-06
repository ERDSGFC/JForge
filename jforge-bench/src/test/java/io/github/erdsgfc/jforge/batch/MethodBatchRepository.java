package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.BatchSize;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

import java.util.List;

/**
 * Batch-test repository overriding the batch size per method: {@code save(List)}
 * is redeclared with an identical signature (legal against {@code BaseRepository})
 * carrying {@code @BatchSize(5)}, which the processor resolves before the
 * type-level and configuration values.
 */
@Dao
public interface MethodBatchRepository extends BaseRepository<BatchUser, Long> {

    @BatchSize(5)
    @Override
    List<BatchUser> save(List<BatchUser> entities);
}