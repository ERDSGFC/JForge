package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.BatchSize;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * Batch-test repository explicitly opting out of batching with
 * {@code @BatchSize(0)}: rows are inserted one by one on a single connection.
 */
@Dao
@BatchSize(0)
public interface NoBatchRepository extends BaseRepository<BatchUser, Long> {
}
