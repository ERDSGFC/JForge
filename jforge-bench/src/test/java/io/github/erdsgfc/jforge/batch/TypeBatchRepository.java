package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.BatchSize;
import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * Batch-test repository overriding the package-level batch size with
 * {@code @BatchSize(3)} on the repository type.
 */
@Dao
@BatchSize(3)
public interface TypeBatchRepository extends BaseRepository<BatchUser, Long> {
}