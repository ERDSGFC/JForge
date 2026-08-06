package io.github.erdsgfc.jforge.batch;

import io.github.erdsgfc.jforge.annotation.Dao;
import io.github.erdsgfc.jforge.core.BaseRepository;

/**
 * Batch-test repository inheriting the package-level {@code @JForgeConfig(batchSize = 2)}
 * from {@code package-info.java}.
 */
@Dao
public interface PackageBatchRepository extends BaseRepository<BatchUser, Long> {
}